#!/usr/bin/env bb
(ns succession.hooks.pre-tool-use
  "Mechanical PreToolUse enforcement hook — the IRREVERSIBLE-OPS SAFETY NET.

   Post-conscience-loop architecture shift: this layer is no longer the
   primary enforcement surface. The semantic + advisory tiers (and the
   LLM judge in PostToolUse) carry the bulk of behavioral enforcement.
   What lives here is exclusively the non-negotiable floor — operations
   that are truly irreversible or dangerous enough that no LLM judgment
   call should be allowed to override them.

   Two sources of blocks:
     1. The compiled mechanical rules in tool-rules.json (user-authored).
     2. The hardcoded critical-safety-patterns below — stays active even
        when tool-rules.json is empty or missing.

   MUST BE FAST — no LLM calls. Pure data processing only.

   Input: JSON on stdin
   Output: {\"decision\": \"block\", \"reason\": \"...\"} or exit 0 to allow"
  (:require [cheshire.core :as json]
            [babashka.fs :as fs]
            [clojure.string :as str]
            [succession.effectiveness :as eff]))

;; --- Hardcoded non-negotiable irreversible-ops floor ---
;;
;; Every pattern here must survive user-authored rule removal. If a
;; user really needs to bypass one of these, they need to do it
;; manually outside of Claude — not via config. Keep this list small
;; and only add patterns for genuinely irreversible damage.
(def critical-safety-patterns
  [{:id "critical-rm-rf-root"
    :pattern #"\brm\s+-rf?\s+/(?:\s|$)"
    :reason "Refusing rm -rf / — irreversible filesystem wipe"}
   {:id "critical-rm-rf-home"
    :pattern #"\brm\s+-rf?\s+(?:~|\$HOME)(?:/\s*|\s*$)"
    :reason "Refusing rm -rf on $HOME — irreversible"}
   {:id "critical-git-force-push-protected"
    :pattern #"\bgit\s+push\s+(?:.*--force|.*-f\b).*\b(main|master|prod|production|release)\b"
    :reason "Refusing git push --force to a protected branch — destructive to shared history"}
   {:id "critical-destructive-sql-no-where"
    :pattern #"(?i)\b(drop\s+(database|table)|truncate\s+table|delete\s+from\s+\S+\s*(?:;|$))"
    :reason "Refusing unconstrained destructive SQL (DROP/TRUNCATE/DELETE without WHERE)"}
   {:id "critical-credential-write"
    :pattern #"(?i)(AWS_SECRET_ACCESS_KEY|ANTHROPIC_API_KEY|OPENAI_API_KEY|-----BEGIN\s+(RSA|OPENSSH|PRIVATE))"
    :reason "Refusing write of detected credential material"}])

(defn check-critical-safety
  "Always-on irreversible-ops floor. Returns a block result or nil."
  [tool-name tool-input]
  (when (= "Bash" tool-name)
    (let [cmd (str (:command tool-input ""))]
      (some (fn [{:keys [id pattern reason]}]
              (when (re-find pattern cmd)
                {:decision "block"
                 :reason (str "Succession (critical floor): " reason)
                 :source id}))
            critical-safety-patterns))))

(defn load-tool-rules [cwd]
  (let [project-file (str cwd "/.succession/compiled/tool-rules.json")
        global-file (str (System/getProperty "user.home") "/.succession/compiled/tool-rules.json")]
    (cond
      (fs/exists? project-file) (json/parse-string (slurp project-file) true)
      (fs/exists? global-file)  (json/parse-string (slurp global-file) true)
      :else nil)))

(defn check-block-tool [rules tool-name]
  (some #(when (= (:block_tool %) tool-name)
           {:decision "block"
            :reason (str "Succession: " (:reason %))
            :source (:source %)})
        rules))

(defn check-bash-pattern [rules command]
  (some #(when-let [pattern (:block_bash_pattern %)]
           (when (re-find (re-pattern pattern) command)
             {:decision "block"
              :reason (str "Succession: " (:reason %))
              :source (:source %)}))
        rules))

(defn check-require-prior-read [rules tool-input transcript-path]
  (let [read-rules (filter :require_prior_read rules)]
    (when (seq read-rules)
      (let [target-file (:file_path tool-input)
            ;; Check transcript for prior Read of this file
            has-prior-read? (when (and target-file transcript-path (fs/exists? transcript-path))
                              (let [lines (->> (str/split-lines (slurp transcript-path))
                                               (take-last 200))]
                                (some (fn [line]
                                        (try
                                          (let [entry (json/parse-string line true)]
                                            (and (= "tool_use" (:type entry))
                                                 (= "Read" (:tool_name entry))
                                                 (= target-file (get-in entry [:tool_input :file_path]))))
                                          (catch Exception _ false)))
                                      lines)))]
        (when (not has-prior-read?)
          {:decision "block"
           :reason (str "Succession: " (:reason (first read-rules)))
           :source (:source (first read-rules))})))))

(defn run
  "Process a pre-tool-use input map. Returns {:decision \"block\" :reason \"...\"} or nil.
   Side effects: logs rule_violated/rule_followed to meta-cognition log.

   Block order:
     1. critical-safety-patterns (hardcoded, always on)
     2. user-authored tool-rules.json (may be empty)"
  [input]
  (let [{:keys [cwd tool_name tool_input session_id transcript_path]} input
        rules (load-tool-rules cwd)
        critical-result (check-critical-safety tool_name tool_input)]
    (if critical-result
      (do
        (try
          (eff/log-event! "rule_violated"
                          {:rule_id (:source critical-result)
                           :context (str tool_name " critical-floor")
                           :detected_by "pre-tool-use-critical"
                           :session (or session_id "unknown")})
          (catch Exception _))
        (select-keys critical-result [:decision :reason]))
      (when rules
        (let [result (or
                      (check-block-tool rules tool_name)
                      (when (= "Bash" tool_name)
                        (check-bash-pattern rules (:command tool_input "")))
                      (when (= "Edit" tool_name)
                        (check-require-prior-read rules tool_input transcript_path)))]
          (if result
          (do
            (try
              (eff/log-event! "rule_violated"
                              {:rule_id (:source result "unknown")
                               :context (str tool_name ":" (subs (str tool_input) 0 (min 100 (count (str tool_input)))))
                               :detected_by "pre-tool-use"
                               :session (or session_id "unknown")})
              (catch Exception _))
            (select-keys result [:decision :reason]))
          (do
            (try
              (doseq [rule rules]
                (eff/log-event! "rule_followed"
                                {:rule_id (:source rule)
                                 :detected_by "pre-tool-use"
                                 :session (or session_id "unknown")}))
              (catch Exception _))
            nil)))))))

(defn -main []
  (try
    (let [input (json/parse-string (slurp *in*) true)
          result (run input)]
      (when result
        (println (json/generate-string result))
        (System/exit 0)))
    (catch Exception e
      (binding [*out* *err*]
        (println (str "Succession pre_tool_use hook error: " (.getMessage e))))
      (System/exit 2))))

(when (= *file* (System/getProperty "babashka.file"))
  (-main))
