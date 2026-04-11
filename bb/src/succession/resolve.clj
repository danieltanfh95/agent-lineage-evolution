(ns succession.resolve
  "Cascade resolution: load rules from global + project dirs, merge with
   CSS-like cascade logic, compile to enforcement artifacts."
  (:require [succession.yaml :as yaml]
            [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]))

(defn load-rules-from-dir
  "Load all .md rule files from a directory, parsing each into a rule map.
   Assigns the given scope to each rule."
  [dir scope]
  (if (fs/exists? dir)
    (->> (fs/glob dir "*.md")
         (map str)
         (keep (fn [f]
                 (when-let [rule (yaml/parse-rule-file f)]
                   (assoc rule :scope scope))))
         vec)
    []))

(defn resolve-rules
  "Cascade merge: project rules override global rules with same id.
   Rules with explicit overrides cancel referenced rules.
   Disabled rules are filtered out."
  [global-rules project-rules]
  (let [proj-idx (into {} (map (juxt :id identity)) project-rules)
        overridden-ids (into #{} (mapcat :overrides) project-rules)
        ;; Keep global rules not overridden by project (by id or explicit overrides)
        surviving-global (remove (fn [r]
                                   (or (contains? proj-idx (:id r))
                                       (contains? overridden-ids (:id r))))
                                 global-rules)
        merged (concat surviving-global project-rules)]
    ;; Filter to enabled rules
    (filterv #(not= false (:enabled %)) merged)))

(defn partition-by-tier
  "Partition resolved rules into {:mechanical [...] :semantic [...] :advisory [...]}."
  [rules]
  {:mechanical (filterv #(= "mechanical" (:enforcement %)) rules)
   :semantic   (filterv #(= "semantic" (:enforcement %)) rules)
   :advisory   (filterv #(= "advisory" (:enforcement %)) rules)})

(defn compile-tool-rules
  "Extract enforcement directives from mechanical rules into a flat list
   suitable for the PreToolUse hook."
  [mechanical-rules]
  (vec
   (for [rule mechanical-rules
         directive (yaml/parse-directives (:body rule))]
     (merge
      (case (:type directive)
        :block-bash-pattern {:block_bash_pattern (:pattern directive)}
        :block-tool         {:block_tool (:tool directive)}
        :require-prior-read {:require_prior_read true}
        {})
      {:reason (:reason directive)
       :source (:id rule)}))))

(defn compile-semantic-rules
  "Compile semantic rules into a markdown string for the prompt hook."
  [semantic-rules]
  (str "# Semantic Rules\n\n"
       "Evaluate the tool call against these rules. If any rule is violated, "
       "respond with {\"ok\": false, \"reason\": \"<which rule and why>\"}.\n\n"
       (str/join "\n"
                 (for [rule semantic-rules]
                   (str "## " (:id rule) "\n"
                        (-> (:body rule "")
                            (str/replace #"## Enforcement[\s\S]*" "")
                            str/trim)
                        "\n")))))

(defn compile-active-rules-digest
  "One-line-per-rule digest of every enabled rule across all tiers.
   Used by the judge ('what's active right now?') and by reinjection as a
   compact drumbeat anchor. Target ~80 chars/line so the full digest
   stays cheap to include on every tool call."
  [all-rules]
  (str "# Active rules (compact)\n"
       "Format: [tier] id — summary\n\n"
       (str/join "\n"
                 (for [rule all-rules
                       :let [tier (or (:enforcement rule) "?")
                             id (:id rule)
                             first-line (-> (:body rule "")
                                            (str/replace #"## Enforcement[\s\S]*" "")
                                            str/trim
                                            str/split-lines
                                            first
                                            (or "")
                                            str/trim)
                             summary (if (> (count first-line) 80)
                                       (str (subs first-line 0 77) "...")
                                       first-line)]]
                   (str "- [" tier "] " id " — " summary)))
       "\n"))

(defn- rule-bullet
  "One markdown bullet for a rule: `- **id** — first-3-lines-of-body`.
   The body has `## Enforcement` and trailing whitespace stripped so
   only the human-facing description appears in the summary."
  [rule]
  (let [body-summary (-> (:body rule "")
                         (str/replace #"## Enforcement[\s\S]*" "")
                         str/trim
                         str/split-lines
                         (->> (take 3)
                              (str/join " ")
                              str/trim))]
    (str "- **" (:id rule) "** — " body-summary)))

(defn compile-advisory-summary
  "Compile advisory + semantic rules into a single markdown summary.
   Each bullet carries the rule id so integrators can trace which rule
   produced a given line. Takes two seqs rather than a single merged
   one so callers can organize their output by tier."
  [advisory-rules semantic-rules]
  (str "# Active Rules\n\n"
       "IMPORTANT: These instructions OVERRIDE any default behavior and you MUST follow them exactly as written.\n\n"
       (when (seq advisory-rules)
         (str "## Advisory\n\n"
              (str/join "\n" (map rule-bullet advisory-rules))
              "\n\n"))
       (when (seq semantic-rules)
         (str "## Semantic (enforced by judge)\n\n"
              (str/join "\n" (map rule-bullet semantic-rules))
              "\n\n"))
       "These rules are mandatory. Every response must demonstrate compliance."))

(defn review-candidates
  "Analyze effectiveness and return rules that need review or promotion."
  [rules]
  (vec
   (for [rule rules
         :let [eff (:effectiveness rule)
               followed (get eff :times-followed 0)
               violated (get eff :times-violated 0)
               total (+ followed violated)]
         :when (>= total 10)
         :let [follow-rate (double (/ followed total))]
         candidate (cond-> []
                     ;; >50% violation rate → flag for review
                     (> (/ violated total) 0.5)
                     (conj {:id (:id rule)
                            :action "review"
                            :follow_rate (format "%.2f" follow-rate)
                            :evaluations total
                            :reason "High violation rate"})

                     ;; Advisory with >80% follow rate → promote candidate
                     (and (= "advisory" (:enforcement rule))
                          (> follow-rate 0.8))
                     (conj {:id (:id rule)
                            :action "promote"
                            :follow_rate (format "%.2f" follow-rate)
                            :evaluations total
                            :reason "High follow rate, promote advisory→semantic"}))]
     candidate)))

(defn resolve-and-compile!
  "Main entry point: load rules, resolve cascade, compile artifacts, write to disk."
  [project-dir]
  (let [global-dir (str (System/getProperty "user.home") "/.succession/rules")
        project-rules-dir (str project-dir "/.succession/rules")
        compiled-dir (str project-dir "/.succession/compiled")
        _ (fs/create-dirs compiled-dir)

        global-rules  (load-rules-from-dir global-dir "global")
        project-rules (load-rules-from-dir project-rules-dir "project")
        resolved (resolve-rules global-rules project-rules)
        {:keys [mechanical semantic advisory]} (partition-by-tier resolved)

        tool-rules (compile-tool-rules mechanical)
        semantic-md (compile-semantic-rules semantic)
        advisory-md (compile-advisory-summary advisory semantic)
        digest-md (compile-active-rules-digest resolved)
        candidates (review-candidates resolved)]

    ;; Write artifacts
    (spit (str compiled-dir "/tool-rules.json") (json/generate-string tool-rules {:pretty true}))
    (spit (str compiled-dir "/semantic-rules.md") semantic-md)
    (spit (str compiled-dir "/advisory-summary.md") advisory-md)
    (spit (str compiled-dir "/active-rules-digest.md") digest-md)
    (spit (str compiled-dir "/review-candidates.json") (json/generate-string candidates {:pretty true}))

    {:total (count resolved)
     :mechanical (count mechanical)
     :semantic (count semantic)
     :advisory (count advisory)
     :candidates (count candidates)}))
