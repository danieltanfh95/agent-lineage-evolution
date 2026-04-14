(ns succession.hook.user-prompt-submit
  "UserPromptSubmit hook — capture user corrections as high-quality
   observations.

   Per plan §UserPromptSubmit, this hook is a detector, not a classifier.
   It scans the submitted prompt with cheap regexes from
   `:correction/patterns`. On a match it appends a `:mark-contradiction`
   delta to `.succession/staging/{sess}/deltas.jsonl`. The delta carries
   the matched pattern and the prompt prefix so the LLM extract at Stop
   (or the next judge cycle) can bind it to the right card.

   We deliberately do NOT write an observation here: observations are
   anchored to a specific card, and a correction prompt arrives before
   any card binding exists. Writing an unbound observation would violate
   `domain/observation`'s string card-id precondition and pollute the
   per-card rollup. Instead, the extract pipeline creates the binding
   when it turns the correction into a real card proposal.

   Emits NO `additionalContext`. The prompt itself is already the
   agent's input — injecting a reminder on top of it is noise. Any
   card-specific reminder is PostToolUse's job via the refresh gate.

   Budget: ≤2s. No LLM. A single pass over `:correction/patterns` + two
   file writes on a hit is well under that.

   Reference: `.plans/succession-identity-cycle.md` §UserPromptSubmit."
  (:require [clojure.string :as str]
            [succession.hook.common :as common]
            [succession.store.staging :as store-staging]))

(defn detect-correction
  "Pure: return the first regex pattern (as a string) that matched the
   prompt, or nil. Caller decides what to do with a match; this function
   only decides IF there was one."
  [prompt patterns]
  (when (and prompt (seq patterns))
    (some (fn [p]
            (try
              (when (re-find (re-pattern p) prompt) p)
              (catch Throwable _ nil)))
          patterns)))

(defn- write-correction-delta!
  [project-root session now matched-pattern prompt]
  (store-staging/append-delta!
    project-root session
    (store-staging/make-delta
      {:id      (str "d-correction-" (random-uuid))
       :at      now
       :kind    :mark-contradiction
       :payload {:matched-pattern matched-pattern
                 :prompt-prefix   (subs prompt 0 (min 240 (count prompt)))
                 :kind            :user-correction}
       :source  :user-correction})))

(defn handle-prompt!
  "Pure-ish entrypoint extracted for tests: takes the same inputs the
   hook sees and performs the I/O. Returns the matched pattern (or nil)
   so tests can assert detection happened."
  [project-root session prompt now config]
  (when-let [hit (detect-correction prompt (:correction/patterns config))]
    (write-correction-delta! project-root session now hit prompt)
    hit))

(defn run
  []
  (try
    (let [input        (common/read-input)
          project-root (common/project-root input)
          session      (or (:session_id input) "unknown")
          prompt       (or (:prompt input) (:user_prompt input) "")
          now          (java.util.Date.)
          cfg          (common/load-config input)]
      (when-not (str/blank? prompt)
        (handle-prompt! project-root session prompt now cfg)))
    (catch Throwable t
      (binding [*out* *err*]
        (println "succession user-prompt-submit error:" (.getMessage t)))))
  nil)
