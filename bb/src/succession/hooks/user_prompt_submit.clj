#!/usr/bin/env bb
(ns succession.hooks.user-prompt-submit
  "UserPromptSubmit hook: classifies which active rules the user's most
   recent prompt implicates and re-anchors their bodies into the next
   turn's context.

   Synchronous because user-prompt-submit latency is bounded by the
   user's reading speed — a few-second Sonnet call is acceptable here.

   No-op when: fewer than 3 rules exist, or the classifier returns
   nothing, or the Succession config has judge.enabled = false (this
   hook shares the judge gate because it is part of the conscience
   loop)."
  (:require [cheshire.core :as json]
            [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]
            [succession.hooks.common :as common]
            [succession.yaml :as yaml]
            [succession.config :as config]))

(defn- load-rule-summaries [cwd]
  (let [global-dir (str (System/getProperty "user.home") "/.succession/rules")
        project-dir (str cwd "/.succession/rules")
        all (concat
              (when (fs/exists? global-dir)
                (keep #(yaml/parse-rule-file (str %)) (fs/glob global-dir "*.md")))
              (when (fs/exists? project-dir)
                (keep #(yaml/parse-rule-file (str %)) (fs/glob project-dir "*.md"))))]
    (vec
      (for [rule all
            :when (and rule (not= false (:enabled rule)))]
        {:id (:id rule)
         :summary (-> (:body rule "")
                      (str/replace #"## Enforcement[\s\S]*" "")
                      str/trim
                      str/split-lines
                      first
                      (or ""))
         :body (:body rule "")}))))

(defn- call-claude [prompt model-id timeout-secs]
  (try
    (let [env (into {} (System/getenv))
          env (assoc env "SUCCESSION_JUDGE_SUBPROCESS" "1")
          result (process/shell {:in prompt
                                 :out :string
                                 :err :string
                                 :timeout (* timeout-secs 1000)
                                 :extra-env env}
                                "claude" "-p" "--model" model-id "--output-format" "json")]
      (when (zero? (:exit result))
        (let [parsed (json/parse-string (:out result) true)]
          (if (sequential? parsed)
            (:result (last parsed))
            (or (:result parsed) (str parsed))))))
    (catch Exception _ nil)))

(defn- build-classifier-prompt [user-msg rules]
  (str "You are Succession's rule classifier. Given the user message, "
       "return the ids of rules whose body is LIKELY to govern how the "
       "assistant should respond.\n\n"
       "Return ONLY a JSON array of rule ids. No markdown, no prose. "
       "If no rules apply, return [].\n\n"
       "USER MESSAGE:\n" user-msg "\n\n"
       "CANDIDATE RULES:\n"
       (str/join "\n"
                 (for [r rules]
                   (str "- " (:id r) ": " (:summary r))))))

(defn- parse-id-array [raw]
  (try
    (let [cleaned (-> raw
                      (str/replace #"(?m)^```json?\s*$" "")
                      (str/replace #"(?m)^```\s*$" "")
                      str/trim)
          parsed (json/parse-string cleaned true)]
      (when (sequential? parsed)
        (vec (keep #(when (string? %) %) parsed))))
    (catch Exception _ [])))

(defn- pick-implicated [user-msg rules model-name]
  (let [model-id (config/resolve-model model-name)
        raw (call-claude (build-classifier-prompt user-msg rules) model-id 15)
        ids (parse-id-array raw)
        id-set (set ids)]
    (filterv #(id-set (:id %)) rules)))

(defn -main []
  (common/require-not-judge-subprocess!)
  (try
    (let [input (json/parse-string (slurp *in*) true)
          {:keys [cwd prompt]} input
          config (config/load-config)
          judge-cfg (:judge config {})
          enabled? (:enabled judge-cfg false)]
      (when (and enabled? prompt)
        (let [rules (load-rule-summaries cwd)]
          (when (>= (count rules) 3)
            (let [model (:model judge-cfg "sonnet")
                  picked (pick-implicated prompt rules model)]
              (when (seq picked)
                ;; Cap the output to ~1.5k tokens worth of rule bodies
                (let [joined (str "[Succession conscience] Rules implicated by your message:\n"
                                  (str/join "\n\n"
                                            (for [r (take 4 picked)]
                                              (str "### " (:id r) "\n"
                                                   (-> (:body r)
                                                       (str/replace #"## Enforcement[\s\S]*" "")
                                                       str/trim)))))
                      capped (if (> (count joined) 6000)
                               (subs joined 0 6000)
                               joined)]
                  (println (json/generate-string
                            {:hookSpecificOutput
                             {:hookEventName "UserPromptSubmit"
                              :additionalContext capped}})))))))))
    (catch Exception e
      (binding [*out* *err*]
        (println (str "Succession user_prompt_submit hook error: " (.getMessage e))))
      (System/exit 0))))

(when (= *file* (System/getProperty "babashka.file"))
  (-main))
