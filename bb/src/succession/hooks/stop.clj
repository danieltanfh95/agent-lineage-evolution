#!/usr/bin/env bb
(ns succession.hooks.stop
  "Stop hook: correction detection, pattern extraction, advisory re-injection.

   Three phases:
   1. Three-tier correction detection (keyword scan → LLM confirm → flag)
   2. Pattern extraction → individual rule files
   3. Advisory rule re-injection via additionalContext

   Input: JSON on stdin with session_id, transcript_path, cwd
   Output: JSON with additionalContext (or exit 0)"
  (:require [cheshire.core :as json]
            [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]
            [succession.resolve :as resolve]
            [succession.yaml :as yaml]
            [succession.effectiveness :as eff]
            [succession.activity :as activity]))

;; --- Config ---

(def default-config
  {:model "sonnet"
   :correctionModel "sonnet"
   :extractEveryKTokens 80000
   :reinjectionInterval 10
   :debug false})

(def correction-keywords
  ["no," "not what" "don't" "dont" "stop " "instead" "wrong"
   "that's not" "thats not" "I said" "i said" "actually,"
   "please don't" "undo" "revert" "go back"])

(def model-ids
  {"haiku"  "claude-haiku-4-5-20251001"
   "sonnet" "claude-sonnet-4-6"
   "opus"   "claude-opus-4-6"})

(defn load-config []
  (let [config-file (str (System/getProperty "user.home") "/.succession/config.json")]
    (if (fs/exists? config-file)
      (merge default-config (json/parse-string (slurp config-file) true))
      default-config)))

;; --- Helpers ---

(defn read-recent-user-messages
  "Read last 3 human messages from transcript."
  [transcript-path]
  (when (and transcript-path (fs/exists? transcript-path))
    (let [lines (->> (str/split-lines (slurp transcript-path))
                     (take-last 10))]
      (->> lines
           (keep (fn [line]
                   (try
                     (let [entry (json/parse-string line true)]
                       (when (= "human" (:type entry))
                         (let [content (get-in entry [:message :content])]
                           (if (vector? content)
                             (str/join " " (keep #(when (= "text" (:type %)) (:text %)) content))
                             (str content)))))
                     (catch Exception _ nil))))
           (take-last 3)
           vec))))

(defn call-claude
  "Call claude CLI with a prompt. Returns the result string or nil."
  [prompt model-id timeout-secs]
  (try
    (let [result (process/shell {:in prompt
                                 :out :string
                                 :err :string
                                 :timeout (* timeout-secs 1000)}
                                "claude" "-p" "--model" model-id "--output-format" "json")]
      (when (zero? (:exit result))
        (let [parsed (json/parse-string (:out result) true)]
          (if (vector? parsed)
            (:result (last parsed))
            (or (:result parsed) (str parsed))))))
    (catch Exception _ nil)))

(defn tier1-keyword-match?
  "Check if any recent user messages contain correction keywords."
  [messages]
  (let [lower-msgs (str/lower-case (str/join " " messages))]
    (some #(str/includes? lower-msgs %) correction-keywords)))

(defn tier2-confirm-correction
  "Use Sonnet to confirm whether a user message is a correction."
  [user-msg model-id]
  (let [prompt (str "Is this user message correcting or criticizing the agent's behavior? "
                     "Reply ONLY \"YES\" or \"NO\".\n\nUSER MESSAGE:\n" user-msg)
        result (call-claude prompt model-id 15)]
    (when result
      (let [upper (str/upper-case (str/trim result))]
        (cond
          (str/includes? upper "YES") :yes
          (str/includes? upper "NO") :no
          :else nil)))))

(defn match-existing-rule
  "Check if a correction matches an existing rule. Returns rule-id or nil."
  [user-msg rule-summaries model-id]
  (when (seq rule-summaries)
    (let [prompt (str "Given this user correction: '" user-msg "'\n"
                      "And these existing rules:\n"
                      (str/join "\n" (map (fn [[id summary]]
                                           (str "- " id ": " summary))
                                         rule-summaries))
                      "\nDoes this correction indicate a violation of an existing rule? "
                      "If yes, return ONLY the rule ID. If no, return ONLY the word null.")
          result (call-claude prompt model-id 15)]
      (when result
        (let [cleaned (-> result str/trim str/lower-case)]
          (when (and (not= "null" cleaned)
                     (seq cleaned))
            ;; Verify the matched ID exists
            (let [valid-ids (set (map first rule-summaries))]
              (when (contains? valid-ids cleaned)
                cleaned))))))))

;; --- Extraction helpers ---

(defn extract-transcript-window
  "Read a window of the transcript file, extracting USER/ASSISTANT messages.
   Returns a string of formatted messages, capped at cap-bytes."
  [transcript-path start-offset cap-bytes]
  (let [content (slurp transcript-path)
        window (subs content (min start-offset (count content)))
        window (subs window 0 (min (count window) cap-bytes))
        lines (str/split-lines window)]
    (->> lines
         (keep (fn [line]
                 (try
                   (let [entry (json/parse-string line true)]
                     (when (#{"human" "assistant"} (:type entry))
                       (let [content-val (get-in entry [:message :content])
                             text (if (vector? content-val)
                                    (str/join " " (keep #(when (= "text" (:type %)) (:text %)) content-val))
                                    (str content-val))]
                         (str (if (= "human" (:type entry)) "USER: " "ASSISTANT: ")
                              (subs text 0 (min (count text) 2000))))))
                   (catch Exception _ nil))))
         (str/join "\n")
         (#(subs % 0 (min (count %) 100000))))))

(defn load-existing-rule-summaries
  "Load existing rule IDs and summaries for deduplication context."
  [& dirs]
  (vec
   (for [dir dirs
         :when (and dir (fs/exists? dir))
         f (fs/glob dir "*.md")
         :let [rule (yaml/parse-rule-file (str f))]
         :when rule]
     (str "- " (:id rule) ": "
          (first (str/split-lines (or (:body rule) "")))))))

(defn build-extraction-prompt
  "Build the LLM prompt for pattern extraction."
  [transcript-window existing-rules]
  (str "You are a behavioral pattern extraction system. Read this conversation transcript and identify patterns in three types:

1. CORRECTIONS — the user told the agent to stop doing something or do something differently
2. CONFIRMATIONS — the user validated a non-obvious choice the agent made
3. PREFERENCES — how the user wants to work (tone, process, style)

For each pattern, determine the enforcement tier:
- \"mechanical\" — can be enforced by blocking a specific tool or bash pattern (e.g., \"never force-push\" → block 'git push --force')
- \"semantic\" — requires LLM judgment to enforce (e.g., \"use Edit instead of sed for source files\")
- \"advisory\" — can only be reminded, not enforced (e.g., \"prefer concise responses\")

For each pattern, classify into one of four knowledge categories:
- \"strategy\" — how the agent approaches problems (workflow patterns, methodologies)
- \"failure-inheritance\" — patterns of failure to avoid (anti-patterns, things that went wrong)
- \"relational-calibration\" — communication style adaptation (tone, verbosity, explanation depth)
- \"meta-cognition\" — which heuristics proved reliable vs. which sounded plausible but failed

EXISTING RULES (do not duplicate):
" (str/join "\n" existing-rules) "

RECENT TRANSCRIPT:
" transcript-window "

Output ONLY valid JSON (no markdown fencing):
{
  \"rules\": [
    {
      \"id\": \"kebab-case-id (e.g., no-force-push, prefer-edit-over-sed)\",
      \"enforcement\": \"mechanical|semantic|advisory\",
      \"category\": \"strategy|failure-inheritance|relational-calibration|meta-cognition\",
      \"type\": \"correction|confirmation|preference\",
      \"scope\": \"global|project\",
      \"summary\": \"one-line rule statement\",
      \"evidence\": \"brief quote from transcript\",
      \"enforcement_directives\": [
        \"block_bash_pattern: regex_pattern\",
        \"block_tool: ToolName\",
        \"require_prior_read: true\",
        \"reason: human-readable explanation\"
      ]
    }
  ]
}

Rules:
- Only extract patterns the user explicitly expressed or clearly demonstrated
- Do not infer preferences from silence or routine acknowledgments
- enforcement_directives are ONLY for mechanical rules — omit for semantic/advisory
- If no patterns found, return {\"rules\": []}
- Do not duplicate rules already in EXISTING RULES above"))

;; --- Main ---

(defn -main []
  (let [input (json/parse-string (slurp *in*) true)
        {:keys [cwd session_id transcript_path]} input
        config (load-config)
        global-dir (str (System/getProperty "user.home") "/.succession")
        project-dir (str cwd "/.succession")
        rules-dir (str project-dir "/rules")
        compiled-dir (str project-dir "/compiled")
        correction-model-id (get model-ids (:correctionModel config) "claude-sonnet-4-6")
        reinjection-interval (:reinjectionInterval config 10)

        ;; State files
        turn-file (str "/tmp/.succession-turns-" session_id)
        correction-flag-file (str "/tmp/.succession-correction-flag-" session_id)
        extract-offset-file (str "/tmp/.succession-extract-offset-" session_id)

        ;; Increment turn counter
        turn-count (if (fs/exists? turn-file)
                     (inc (parse-long (str/trim (slurp turn-file))))
                     1)
        _ (spit turn-file (str turn-count))

        notification-msg (atom nil)
        additional-context (atom nil)]

    ;; Guard: bail if no transcript
    (when (and transcript_path (fs/exists? transcript_path))
      (fs/create-dirs rules-dir)
      (fs/create-dirs compiled-dir)
      (fs/create-dirs (str project-dir "/log"))

      ;; ========================================
      ;; PHASE 1: CORRECTION DETECTION
      ;; ========================================
      (let [recent-msgs (read-recent-user-messages transcript_path)]
        (when (tier1-keyword-match? recent-msgs)
          (let [latest-msg (last recent-msgs)]
            ;; Log tier1 match
            (try (activity/log-activity-event! "correction_tier1" cwd session_id
                   {:turn turn-count
                    :user_msg_snippet (subs latest-msg 0 (min 100 (count latest-msg)))})
                 (catch Exception _))

            (let [verdict (tier2-confirm-correction (subs latest-msg 0 (min 500 (count latest-msg)))
                                                    correction-model-id)]
              ;; Log tier2 result
              (try (activity/log-activity-event! "correction_tier2" cwd session_id
                     {:turn turn-count
                      :verdict (if verdict (name verdict) "UNKNOWN")})
                   (catch Exception _))

              (when (= :yes verdict)
                ;; Semantic violation matching
                (let [rule-summaries
                      (vec (for [dir [rules-dir (str global-dir "/rules")]
                                 :when (fs/exists? dir)
                                 f (fs/glob dir "*.md")
                                 :let [rule (yaml/parse-rule-file (str f))]
                                 :when rule]
                             [(:id rule) (first (str/split-lines (or (:body rule) "")))]))]

                  (if-let [matched (match-existing-rule latest-msg rule-summaries correction-model-id)]
                    (do
                      (eff/log-event! "rule_violated"
                                      {:rule_id matched
                                       :context latest-msg
                                       :detected_by "correction-matching"
                                       :session session_id})
                      (eff/log-event! "correction_matched"
                                      {:rule_id matched
                                       :user_msg (subs latest-msg 0 (min 200 (count latest-msg)))
                                       :session session_id})
                      (reset! notification-msg (str "Matched correction to existing rule: " matched)))
                    (do
                      (spit correction-flag-file "1")
                      (reset! notification-msg "Noticed a correction, will learn from it soon")))))))))

      ;; ========================================
      ;; PHASE 2: PATTERN EXTRACTION → RULE FILES
      ;; Runs when transcript has grown enough (or sooner if correction flagged)
      ;; ========================================
      (let [transcript-size (fs/size transcript_path)
            last-extract-offset (if (fs/exists? extract-offset-file)
                                  (parse-long (str/trim (slurp extract-offset-file)))
                                  0)
            transcript-growth (- transcript-size last-extract-offset)
            extract-threshold (:extractEveryKTokens config 80000)
            ;; Reduce threshold 5x if a correction was recently flagged
            effective-threshold (if (fs/exists? correction-flag-file)
                                  (quot extract-threshold 5)
                                  extract-threshold)]

        (when (>= transcript-growth effective-threshold)
          (let [window-start (max last-extract-offset
                                  (- transcript-size 204800))
                ;; Read transcript window and extract user/assistant messages
                transcript-window (extract-transcript-window transcript_path window-start 204800)]

            (if (str/blank? transcript-window)
              ;; Empty window — just update offset
              (spit extract-offset-file (str transcript-size))
              ;; Run extraction
              (let [existing-rules (load-existing-rule-summaries rules-dir (str global-dir "/rules"))
                    extraction-model-id (get model-ids (:model config "sonnet") "claude-sonnet-4-6")
                    extract-prompt (build-extraction-prompt transcript-window existing-rules)
                    raw-result (call-claude extract-prompt extraction-model-id 60)
                    ;; Strip markdown fencing if present
                    cleaned (when raw-result
                              (-> raw-result
                                  (str/replace #"(?m)^```json?\s*$" "")
                                  (str/replace #"(?m)^```\s*$" "")
                                  str/trim))
                    parsed (try
                             (when cleaned (json/parse-string cleaned true))
                             (catch Exception _
                               (eff/log-event! "extraction_failed"
                                                {:error "Invalid JSON from extraction"
                                                 :session session_id})
                               (try (activity/log-activity-event! "extraction_failed" cwd session_id
                                      {:turn turn-count :error "Invalid JSON from extraction"})
                                    (catch Exception _))
                               nil))]

                ;; Write extracted rules
                (when-let [rules-list (seq (:rules parsed))]
                  (let [rules-written (atom 0)]
                    (doseq [rule rules-list]
                      (let [{:keys [id enforcement category type scope summary evidence
                                    enforcement_directives]} rule
                            target-dir (if (= "global" scope)
                                         (str global-dir "/rules")
                                         rules-dir)
                            rule-file (str target-dir "/" id ".md")]
                        ;; Skip if rule already exists
                        (when-not (fs/exists? rule-file)
                          (fs/create-dirs target-dir)
                          (let [directives-section (when (and (= "mechanical" enforcement)
                                                             (seq enforcement_directives))
                                                    (str "\n\n## Enforcement\n"
                                                         (str/join "\n"
                                                                   (map #(str "- " %) enforcement_directives))))
                                rule-map {:id id
                                          :scope (or scope "project")
                                          :enforcement enforcement
                                          :category (or category "strategy")
                                          :type type
                                          :source {:session session_id
                                                   :timestamp (str (java.time.Instant/now))
                                                   :evidence (or evidence "")}
                                          :overrides []
                                          :enabled true
                                          :effectiveness {:times-followed 0
                                                          :times-violated 0
                                                          :times-overridden 0
                                                          :last-evaluated nil}
                                          :body (str (or summary "")
                                                     (or directives-section ""))}]
                            (yaml/write-rule-file rule-file rule-map)
                            (swap! rules-written inc)

                            ;; Log rule creation
                            (eff/log-event! "rule_created"
                                            {:rule_id id
                                             :category (or category "strategy")
                                             :source "extraction"
                                             :session session_id})))))

                    (when (pos? @rules-written)
                      (reset! notification-msg
                              (str (when @notification-msg (str @notification-msg "; "))
                                   "Extracted " @rules-written " new rule(s)"))
                      ;; Log extraction event
                      (try (activity/log-activity-event! "extraction" cwd session_id
                             {:turn turn-count
                              :rules_written @rules-written
                              :total_found (count rules-list)
                              :model extraction-model-id})
                           (catch Exception _))
                      ;; Re-compile rules after extraction
                      (try (resolve/resolve-and-compile! cwd) (catch Exception _)))))

                ;; Update offset and consume correction flag
                (spit extract-offset-file (str transcript-size))
                (when (fs/exists? correction-flag-file)
                  (fs/delete correction-flag-file)))))))

      ;; ========================================
      ;; PHASE 3: ADVISORY RE-INJECTION
      ;; ========================================
      (when (zero? (mod turn-count reinjection-interval))
        (let [advisory-file (str compiled-dir "/advisory-summary.md")]
          (when (and (fs/exists? advisory-file)
                     (pos? (fs/size advisory-file)))
            (reset! additional-context (slurp advisory-file))))

        ;; Also update effectiveness counters periodically
        (try
          (eff/update-effectiveness-counters! rules-dir (str global-dir "/rules"))
          (catch Exception _)))

      ;; ========================================
      ;; OUTPUT
      ;; ========================================
      (let [ctx @additional-context
            msg @notification-msg]
        (when (or ctx msg)
          (let [full-ctx (cond-> ""
                           ctx (str ctx)
                           msg (str "\n\n[Succession] " msg))]
            (println (json/generate-string
                      {:hookSpecificOutput
                       {:additionalContext full-ctx}}))))))))

(when (= *file* (System/getProperty "babashka.file"))
  (-main))
