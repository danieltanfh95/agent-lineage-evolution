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

(defn compile-advisory-summary
  "Compile advisory + semantic rules into a reminder summary."
  [advisory-rules semantic-rules]
  (str "# Active Rules (Reminder)\n\n"
       "The following rules are currently active. Follow them in your responses.\n\n"
       (str/join "\n"
                 (for [rule (concat advisory-rules semantic-rules)]
                   (let [body-summary (-> (:body rule "")
                                          str/split-lines
                                          (->> (take 3)
                                               (str/join " ")))]
                     (str "- **" (:id rule) "**: " body-summary))))))

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
        candidates (review-candidates resolved)]

    ;; Write artifacts
    (spit (str compiled-dir "/tool-rules.json") (json/generate-string tool-rules {:pretty true}))
    (spit (str compiled-dir "/semantic-rules.md") semantic-md)
    (spit (str compiled-dir "/advisory-summary.md") advisory-md)
    (spit (str compiled-dir "/review-candidates.json") (json/generate-string candidates {:pretty true}))

    {:total (count resolved)
     :mechanical (count mechanical)
     :semantic (count semantic)
     :advisory (count advisory)
     :candidates (count candidates)}))
