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
            [succession.effectiveness :as eff]))

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
          (let [latest-msg (last recent-msgs)
                verdict (tier2-confirm-correction (subs latest-msg 0 (min 500 (count latest-msg)))
                                                  correction-model-id)]
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
                    (reset! notification-msg "Noticed a correction, will learn from it soon"))))))))

      ;; ========================================
      ;; PHASE 2: ADVISORY RE-INJECTION
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
