(ns succession.integration-test
  "End-to-end integration tests for Succession babashka hooks.
   Each test creates a full fixture directory structure, writes rules,
   compiles them, and runs hooks against real files."
  (:require [clojure.test :refer [deftest is testing]]
            [succession.hooks.pre-tool-use :as ptu]
            [succession.hooks.session-start :as ss]
            [succession.hooks.stop :as stop]
            [succession.resolve :as resolve]
            [succession.yaml :as yaml]
            [succession.effectiveness :as eff]
            [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]))

;; ============================================================
;; HELPERS
;; ============================================================

(defn create-test-fixture!
  "Build a full temp directory structure mimicking a real succession install.
   Returns a map of paths."
  []
  (let [root (str (fs/create-temp-dir {:prefix "succession-integ-"}))
        home (str root "/fakehome")
        project (str root "/project")]
    (doseq [d [(str home "/.succession/rules")
               (str home "/.succession/compiled")
               (str home "/.succession/skills")
               (str home "/.succession/log")
               (str project "/.succession/rules")
               (str project "/.succession/compiled")
               (str project "/.succession/skills")
               (str project "/.succession/log")
               (str root "/transcripts")]]
      (fs/create-dirs d))
    {:root root
     :home home
     :project project
     :global-rules (str home "/.succession/rules")
     :project-rules (str project "/.succession/rules")
     :compiled (str project "/.succession/compiled")
     :transcript-dir (str root "/transcripts")}))

(defn create-rule-file!
  "Write a rule .md file with full YAML frontmatter."
  [dir id enforcement body & {:keys [scope category type overrides enabled]
                               :or {scope "project" category "strategy" type "correction"
                                    overrides [] enabled true}}]
  (let [rule {:id id :scope scope :enforcement enforcement
              :category category :type type
              :source {:session "fixture" :timestamp "" :evidence "test fixture"}
              :overrides overrides :enabled enabled
              :effectiveness {:times-followed 0 :times-violated 0
                              :times-overridden 0 :last-evaluated nil}
              :body body}]
    (yaml/write-rule-file (str dir "/" id ".md") rule)))

(defn create-transcript!
  "Write a JSONL transcript. Each entry is a map with at least :type and :content."
  [dir filename entries]
  (let [path (str dir "/" filename)]
    (spit path
          (str/join "\n"
                    (map (fn [e]
                           (json/generate-string
                            (cond-> {:type (:type e)}
                              (:content e)
                              (assoc :message {:content (:content e)})
                              (:tool_name e)
                              (assoc :tool_name (:tool_name e))
                              (:tool_input e)
                              (assoc :tool_input (:tool_input e))
                              (:cwd e)
                              (assoc :cwd (:cwd e)))))
                         entries)))
    path))

(defn run-hook
  "Call a hook's -main with JSON on stdin, capture stdout."
  [hook-main-fn json-str]
  (with-out-str
    (binding [*in* (java.io.BufferedReader.
                    (java.io.StringReader. json-str))]
      (hook-main-fn))))

(defn parse-output
  "Parse hook stdout. Returns parsed JSON map or nil if empty."
  [stdout-str]
  (when-not (str/blank? stdout-str)
    (json/parse-string (str/trim stdout-str) true)))

(defn with-home
  "Execute f with user.home set to home-path, restoring afterward."
  [home-path f]
  (let [orig (System/getProperty "user.home")]
    (try
      (System/setProperty "user.home" home-path)
      (f)
      (finally
        (System/setProperty "user.home" orig)))))

(defn cleanup-state-files!
  "Remove /tmp/.succession-* state files for the given session-id."
  [session-id]
  (doseq [prefix ["turns" "extract-offset" "correction-flag"]]
    (let [f (str "/tmp/.succession-" prefix "-" session-id)]
      (when (fs/exists? f) (fs/delete f)))))

(defn unique-session []
  (str "test-" (System/nanoTime)))

(defn mock-call-claude
  "Returns a mock for stop/call-claude that dispatches on prompt content."
  [responses]
  (fn [prompt _model-id _timeout]
    (cond
      (str/includes? prompt "YES\" or \"NO\"")
      (:tier2 responses)

      (str/includes? prompt "violation of an existing rule")
      (:matching responses)

      (str/includes? prompt "behavioral pattern extraction")
      (:extraction responses)

      :else nil)))

(defn read-jsonl
  "Read a JSONL file and return a seq of parsed maps."
  [path]
  (when (fs/exists? path)
    (->> (str/split-lines (slurp path))
         (remove str/blank?)
         (map #(json/parse-string % true)))))

;; ============================================================
;; PRE_TOOL_USE INTEGRATION TESTS
;; ============================================================

(deftest ptu-block-bash-pattern-integration
  (let [fix (create-test-fixture!)
        sess (unique-session)]
    (try
      (with-home (:home fix)
        (fn []
          (create-rule-file! (:project-rules fix) "no-force-push" "mechanical"
            "Never force push.\n\n## Enforcement\n- block_bash_pattern: git push.*(--force|-f)\n- reason: Force-push blocked")
          (resolve/resolve-and-compile! (:project fix))

          (let [result (ptu/run {:cwd (:project fix)
                                  :tool_name "Bash"
                                  :tool_input {:command "git push --force origin main"}
                                  :session_id sess})]
            (is (= "block" (:decision result)))
            (is (str/includes? (:reason result) "Force-push blocked")))))
      (finally
        (cleanup-state-files! sess)
        (fs/delete-tree (:root fix))))))

(deftest ptu-block-tool-integration
  (let [fix (create-test-fixture!)
        sess (unique-session)]
    (try
      (with-home (:home fix)
        (fn []
          (create-rule-file! (:project-rules fix) "no-agents" "mechanical"
            "No subagents.\n\n## Enforcement\n- block_tool: Agent\n- reason: Agents not allowed")
          (resolve/resolve-and-compile! (:project fix))

          (let [result (ptu/run {:cwd (:project fix)
                                  :tool_name "Agent"
                                  :tool_input {}
                                  :session_id sess})]
            (is (= "block" (:decision result)))
            (is (str/includes? (:reason result) "Agents not allowed")))))
      (finally
        (cleanup-state-files! sess)
        (fs/delete-tree (:root fix))))))

(deftest ptu-allow-non-matching-integration
  (let [fix (create-test-fixture!)
        sess (unique-session)]
    (try
      (with-home (:home fix)
        (fn []
          (create-rule-file! (:project-rules fix) "no-agents" "mechanical"
            "No subagents.\n\n## Enforcement\n- block_tool: Agent\n- reason: Agents not allowed")
          (resolve/resolve-and-compile! (:project fix))

          (let [result (ptu/run {:cwd (:project fix)
                                  :tool_name "Read"
                                  :tool_input {:file_path "/foo.clj"}
                                  :session_id sess})]
            (is (nil? result)))))
      (finally
        (cleanup-state-files! sess)
        (fs/delete-tree (:root fix))))))

(deftest ptu-no-rules-file-integration
  (let [fix (create-test-fixture!)
        sess (unique-session)]
    (try
      (with-home (:home fix)
        (fn []
          ;; No resolve, no tool-rules.json
          (let [result (ptu/run {:cwd (:project fix)
                                  :tool_name "Bash"
                                  :tool_input {:command "rm -rf /"}
                                  :session_id sess})]
            (is (nil? result)))))
      (finally
        (cleanup-state-files! sess)
        (fs/delete-tree (:root fix))))))

(deftest ptu-require-prior-read-integration
  (let [fix (create-test-fixture!)
        sess (unique-session)]
    (try
      (with-home (:home fix)
        (fn []
          (create-rule-file! (:project-rules fix) "read-before-edit" "mechanical"
            "Must read files before editing.\n\n## Enforcement\n- require_prior_read: true\n- reason: Read the file first")
          (resolve/resolve-and-compile! (:project fix))

          ;; Transcript WITHOUT a prior Read of /foo.clj
          (let [transcript (create-transcript! (:transcript-dir fix) "sess.jsonl"
                             [{:type "human" :content "edit foo.clj"}
                              {:type "tool_use" :tool_name "Bash" :tool_input {:command "ls"}}])
                result (ptu/run {:cwd (:project fix)
                                  :tool_name "Edit"
                                  :tool_input {:file_path "/foo.clj"}
                                  :session_id sess
                                  :transcript_path transcript})]
            (testing "blocks Edit without prior Read"
              (is (= "block" (:decision result)))))

          ;; Transcript WITH a prior Read of /foo.clj
          (let [transcript (create-transcript! (:transcript-dir fix) "sess2.jsonl"
                             [{:type "tool_use" :tool_name "Read" :tool_input {:file_path "/foo.clj"}}
                              {:type "human" :content "now edit it"}])
                result (ptu/run {:cwd (:project fix)
                                  :tool_name "Edit"
                                  :tool_input {:file_path "/foo.clj"}
                                  :session_id sess
                                  :transcript_path transcript})]
            (testing "allows Edit after Read of same file"
              (is (nil? result))))))
      (finally
        (cleanup-state-files! sess)
        (fs/delete-tree (:root fix))))))

(deftest ptu-global-fallback-integration
  (let [fix (create-test-fixture!)
        sess (unique-session)]
    (try
      (with-home (:home fix)
        (fn []
          ;; Rule only in global dir
          (create-rule-file! (:global-rules fix) "no-agents" "mechanical"
            "No agents.\n\n## Enforcement\n- block_tool: Agent\n- reason: Global block")
          ;; Compile to global compiled dir
          (let [global-compiled (str (:home fix) "/.succession/compiled")]
            (fs/create-dirs global-compiled)
            (resolve/resolve-and-compile! (:project fix)))

          (let [result (ptu/run {:cwd (:project fix)
                                  :tool_name "Agent"
                                  :tool_input {}
                                  :session_id sess})]
            (is (= "block" (:decision result))))))
      (finally
        (cleanup-state-files! sess)
        (fs/delete-tree (:root fix))))))

(deftest ptu-meta-cognition-logging-integration
  (let [fix (create-test-fixture!)
        sess (unique-session)]
    (try
      (with-home (:home fix)
        (fn []
          (create-rule-file! (:project-rules fix) "no-force-push" "mechanical"
            "No force push.\n\n## Enforcement\n- block_bash_pattern: git push.*--force\n- reason: Blocked")
          (resolve/resolve-and-compile! (:project fix))

          (ptu/run {:cwd (:project fix)
                    :tool_name "Bash"
                    :tool_input {:command "git push --force"}
                    :session_id sess})

          (let [log-file (str (:home fix) "/.succession/log/meta-cognition.jsonl")
                events (read-jsonl log-file)]
            (is (some? events))
            (is (some #(and (= "rule_violated" (:event %))
                            (= "no-force-push" (:rule_id %)))
                      events)))))
      (finally
        (cleanup-state-files! sess)
        (fs/delete-tree (:root fix))))))

;; ============================================================
;; SESSION_START INTEGRATION TESTS
;; ============================================================

(deftest session-start-full-flow-integration
  (let [fix (create-test-fixture!)
        sess (unique-session)]
    (try
      (with-home (:home fix)
        (fn []
          (create-rule-file! (:project-rules fix) "prefer-concise" "advisory"
            "Keep responses concise.")
          (create-rule-file! (:project-rules fix) "no-force-push" "mechanical"
            "No force push.\n\n## Enforcement\n- block_bash_pattern: git push.*--force\n- reason: Blocked")

          (let [output (run-hook ss/-main
                         (json/generate-string {:cwd (:project fix) :session_id sess}))
                parsed (parse-output output)]
            ;; Compiled artifacts should exist
            (is (fs/exists? (str (:compiled fix) "/tool-rules.json")))
            (is (fs/exists? (str (:compiled fix) "/advisory-summary.md")))

            ;; Output should have advisory rules
            (is (some? parsed))
            (let [ctx (get-in parsed [:hookSpecificOutput :additionalContext])]
              (is (str/includes? ctx "ACTIVE RULES"))
              (is (str/includes? ctx "prefer-concise"))))))
      (finally
        (cleanup-state-files! sess)
        (fs/delete-tree (:root fix))))))

(deftest session-start-skill-loading-integration
  (let [fix (create-test-fixture!)
        sess (unique-session)]
    (try
      (with-home (:home fix)
        (fn []
          ;; Place a skill in the project succession skills dir
          (let [skill-dir (str (:project fix) "/.succession/skills/my-skill")]
            (fs/create-dirs skill-dir)
            (spit (str skill-dir "/SKILL.md") "# My Skill\nDo the thing."))

          (let [output (run-hook ss/-main
                         (json/generate-string {:cwd (:project fix) :session_id sess}))]
            ;; Verify skill was copied to .claude/skills/
            (let [target (str (:project fix) "/.claude/skills/my-skill/SKILL.md")]
              (is (fs/exists? target) (str "Expected skill at " target " but it was not found. Output: " output))
              (when (fs/exists? target)
                (is (str/includes? (slurp target) "My Skill")))))))
      (finally
        (cleanup-state-files! sess)
        (fs/delete-tree (:root fix))))))

(deftest session-start-empty-state-integration
  (let [fix (create-test-fixture!)
        sess (unique-session)]
    (try
      (with-home (:home fix)
        (fn []
          ;; No rules, no skills — just the dirs
          (let [output (run-hook ss/-main
                         (json/generate-string {:cwd (:project fix) :session_id sess}))
                parsed (parse-output output)]
            ;; Should still produce output with resolution note
            (is (some? parsed))
            (let [ctx (get-in parsed [:hookSpecificOutput :additionalContext])]
              (is (str/includes? ctx "RULE RESOLUTION"))))))
      (finally
        (cleanup-state-files! sess)
        (fs/delete-tree (:root fix))))))

(deftest session-start-activity-logging-integration
  (let [fix (create-test-fixture!)
        sess (unique-session)]
    (try
      (with-home (:home fix)
        (fn []
          (run-hook ss/-main
            (json/generate-string {:cwd (:project fix) :session_id sess}))

          (let [log-file (str (:project fix) "/.succession/log/succession-activity.jsonl")
                events (read-jsonl log-file)]
            (is (some? events))
            (is (some #(= "session_start" (:event %)) events)))))
      (finally
        (cleanup-state-files! sess)
        (fs/delete-tree (:root fix))))))

;; ============================================================
;; STOP HOOK INTEGRATION TESTS
;; ============================================================

(deftest stop-correction-detection-integration
  (let [fix (create-test-fixture!)
        sess (unique-session)]
    (try
      (with-home (:home fix)
        (fn []
          (let [transcript (create-transcript! (:transcript-dir fix) "sess.jsonl"
                             [{:type "human" :content "hello"}
                              {:type "assistant" :content "hi"}
                              {:type "human" :content "no, don't do that"}])]
            (with-redefs [stop/call-claude (mock-call-claude {:tier2 "YES"})]
              (run-hook stop/-main
                (json/generate-string {:cwd (:project fix)
                                        :session_id sess
                                        :transcript_path transcript})))

            ;; Correction flag should be set
            (is (fs/exists? (str "/tmp/.succession-correction-flag-" sess))))))
      (finally
        (cleanup-state-files! sess)
        (fs/delete-tree (:root fix))))))

(deftest stop-correction-matching-integration
  (let [fix (create-test-fixture!)
        sess (unique-session)]
    (try
      (with-home (:home fix)
        (fn []
          ;; Create existing rule
          (create-rule-file! (:project-rules fix) "no-agents" "advisory"
            "Don't use subagents.")

          (let [transcript (create-transcript! (:transcript-dir fix) "sess.jsonl"
                             [{:type "human" :content "hello"}
                              {:type "assistant" :content "let me use an agent"}
                              {:type "human" :content "no, don't use agents"}])]
            (with-redefs [stop/call-claude (mock-call-claude {:tier2 "YES"
                                                               :matching "no-agents"})]
              (run-hook stop/-main
                (json/generate-string {:cwd (:project fix)
                                        :session_id sess
                                        :transcript_path transcript})))

            ;; Should NOT create correction flag (matched existing rule)
            (is (not (fs/exists? (str "/tmp/.succession-correction-flag-" sess))))

            ;; Should log rule_violated in meta-cognition
            (let [events (read-jsonl (str (:home fix) "/.succession/log/meta-cognition.jsonl"))]
              (is (some #(and (= "rule_violated" (:event %))
                              (= "no-agents" (:rule_id %)))
                        events))))))
      (finally
        (cleanup-state-files! sess)
        (fs/delete-tree (:root fix))))))

(deftest stop-advisory-reinjection-integration
  (let [fix (create-test-fixture!)
        sess (unique-session)]
    (try
      (with-home (:home fix)
        (fn []
          ;; Write advisory summary
          (spit (str (:compiled fix) "/advisory-summary.md")
                "# Active Rules\n- prefer-concise: Keep it short")

          ;; Set turn counter to reinjection interval - 1 (so next turn triggers)
          (spit (str "/tmp/.succession-turns-" sess) "9")

          (let [transcript (create-transcript! (:transcript-dir fix) "sess.jsonl"
                             [{:type "human" :content "looks good"}])
                output (run-hook stop/-main
                         (json/generate-string {:cwd (:project fix)
                                                 :session_id sess
                                                 :transcript_path transcript}))
                parsed (parse-output output)]
            (is (some? parsed))
            (let [ctx (get-in parsed [:hookSpecificOutput :additionalContext])]
              (is (str/includes? ctx "Active Rules"))
              (is (str/includes? ctx "prefer-concise"))))))
      (finally
        (cleanup-state-files! sess)
        (fs/delete-tree (:root fix))))))

(deftest stop-turn-counter-integration
  (let [fix (create-test-fixture!)
        sess (unique-session)]
    (try
      (with-home (:home fix)
        (fn []
          (let [transcript (create-transcript! (:transcript-dir fix) "sess.jsonl"
                             [{:type "human" :content "hello"}])
                input (json/generate-string {:cwd (:project fix)
                                              :session_id sess
                                              :transcript_path transcript})]
            ;; Run twice
            (run-hook stop/-main input)
            (run-hook stop/-main input)

            (let [turns (parse-long (str/trim (slurp (str "/tmp/.succession-turns-" sess))))]
              (is (= 2 turns))))))
      (finally
        (cleanup-state-files! sess)
        (fs/delete-tree (:root fix))))))

(deftest stop-extraction-pipeline-integration
  (let [fix (create-test-fixture!)
        sess (unique-session)]
    (try
      (with-home (:home fix)
        (fn []
          ;; Create a large-enough transcript
          (let [entries (vec (for [i (range 500)]
                              {:type (if (even? i) "human" "assistant")
                               :content (apply str (repeat 200 (str "message " i " ")))}))
                transcript (create-transcript! (:transcript-dir fix) "sess.jsonl" entries)]

            ;; Set correction flag to reduce threshold
            (spit (str "/tmp/.succession-correction-flag-" sess) "1")

            ;; Write config with small threshold
            (spit (str (:home fix) "/.succession/config.json")
                  (json/generate-string {:extractEveryKTokens 1000}))

            (let [extraction-json (json/generate-string
                                    {:rules [{:id "extracted-rule"
                                              :enforcement "advisory"
                                              :category "strategy"
                                              :type "correction"
                                              :scope "project"
                                              :summary "Don't do the thing"
                                              :evidence "User said no"}]})]
              (with-redefs [stop/call-claude (mock-call-claude {:extraction extraction-json})]
                (run-hook stop/-main
                  (json/generate-string {:cwd (:project fix)
                                          :session_id sess
                                          :transcript_path transcript}))))

            ;; New rule should be created
            (is (fs/exists? (str (:project-rules fix) "/extracted-rule.md")))

            ;; Correction flag should be consumed
            (is (not (fs/exists? (str "/tmp/.succession-correction-flag-" sess))))

            ;; Offset file should be updated
            (is (fs/exists? (str "/tmp/.succession-extract-offset-" sess))))))
      (finally
        (cleanup-state-files! sess)
        (fs/delete-tree (:root fix))))))

(deftest stop-no-transcript-integration
  (let [fix (create-test-fixture!)
        sess (unique-session)]
    (try
      (with-home (:home fix)
        (fn []
          (let [output (run-hook stop/-main
                         (json/generate-string {:cwd (:project fix)
                                                 :session_id sess
                                                 :transcript_path nil}))]
            (is (str/blank? output)))))
      (finally
        (cleanup-state-files! sess)
        (fs/delete-tree (:root fix))))))

;; ============================================================
;; RESOLVE ROUND-TRIP TESTS
;; ============================================================

(deftest resolve-project-wins-integration
  (let [fix (create-test-fixture!)
        sess (unique-session)]
    (try
      (with-home (:home fix)
        (fn []
          ;; Same ID in both dirs, different body
          (create-rule-file! (:global-rules fix) "my-rule" "advisory"
            "Global version of the rule.")
          (create-rule-file! (:project-rules fix) "my-rule" "advisory"
            "Project version of the rule.")

          (resolve/resolve-and-compile! (:project fix))

          (let [advisory (slurp (str (:compiled fix) "/advisory-summary.md"))]
            (is (str/includes? advisory "Project version"))
            (is (not (str/includes? advisory "Global version"))))))
      (finally
        (cleanup-state-files! sess)
        (fs/delete-tree (:root fix))))))

(deftest resolve-explicit-overrides-integration
  (let [fix (create-test-fixture!)
        sess (unique-session)]
    (try
      (with-home (:home fix)
        (fn []
          (create-rule-file! (:global-rules fix) "old-rule" "advisory"
            "This should be overridden.")
          (create-rule-file! (:project-rules fix) "new-rule" "advisory"
            "This replaces old-rule."
            :overrides ["old-rule"])

          (resolve/resolve-and-compile! (:project fix))

          ;; old-rule should not appear as a standalone rule entry in advisory
          (let [advisory (slurp (str (:compiled fix) "/advisory-summary.md"))]
            (is (str/includes? advisory "new-rule"))
            ;; old-rule should not have its own **old-rule** entry
            (is (not (str/includes? advisory "**old-rule**"))))))
      (finally
        (cleanup-state-files! sess)
        (fs/delete-tree (:root fix))))))

(deftest resolve-disabled-filtered-integration
  (let [fix (create-test-fixture!)
        sess (unique-session)]
    (try
      (with-home (:home fix)
        (fn []
          (create-rule-file! (:project-rules fix) "disabled-rule" "advisory"
            "This is disabled." :enabled false)
          (create-rule-file! (:project-rules fix) "active-rule" "advisory"
            "This is active.")

          (resolve/resolve-and-compile! (:project fix))

          (let [advisory (slurp (str (:compiled fix) "/advisory-summary.md"))]
            (is (str/includes? advisory "active-rule"))
            (is (not (str/includes? advisory "disabled-rule"))))))
      (finally
        (cleanup-state-files! sess)
        (fs/delete-tree (:root fix))))))

(deftest resolve-mechanical-to-pretooluse-integration
  (let [fix (create-test-fixture!)
        sess (unique-session)]
    (try
      (with-home (:home fix)
        (fn []
          (create-rule-file! (:project-rules fix) "no-rm-rf" "mechanical"
            "No rm -rf.\n\n## Enforcement\n- block_bash_pattern: rm\\s+-rf\n- reason: Dangerous command")

          ;; Resolve compiles to tool-rules.json
          (resolve/resolve-and-compile! (:project fix))

          ;; Verify tool-rules.json exists and has content
          (let [tool-rules-file (str (:compiled fix) "/tool-rules.json")]
            (is (fs/exists? tool-rules-file))
            (let [rules (json/parse-string (slurp tool-rules-file) true)]
              (is (pos? (count rules)))))

          ;; Feed to pre_tool_use
          (let [result (ptu/run {:cwd (:project fix)
                                  :tool_name "Bash"
                                  :tool_input {:command "rm -rf /tmp/stuff"}
                                  :session_id sess})]
            (is (= "block" (:decision result)))
            (is (str/includes? (:reason result) "Dangerous command")))))
      (finally
        (cleanup-state-files! sess)
        (fs/delete-tree (:root fix))))))
