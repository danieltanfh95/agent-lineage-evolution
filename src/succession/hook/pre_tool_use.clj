(ns succession.hook.pre-tool-use
  "PreToolUse hook — pure salient-card lookup for the upcoming tool call.

   Per plan §PreToolUse: this is a pure lookup. No LLM, no disk writes,
   no `updatedInput` mutation (explicit non-goal per Finding 3 — identity
   is advisory, not mechanical).

   Steps:
     1. Load promoted cards + staging snapshot (via common/score-cards).
     2. Build a tool descriptor from the upcoming tool call for
        fingerprint matching.
     3. Apply `domain/salience/rank` with the descriptor.
     4. Render `~300`-byte reminder via `domain/render/salient-reminder`.
     5. Emit `{:hookSpecificOutput {:additionalContext ...}}`.

   Budget: <1s. Uses exactly the same salience+render pipeline as
   post-tool-use sync lane, so the two hooks deliberately stay in lock-
   step. Both hooks share the same byte-delta refresh gate so parallel
   tool batches dedup naturally and total pacing is ~1 blob per 200KB
   of transcript growth.

   Reference: `.plans/succession-identity-cycle.md` §PreToolUse."
  (:require [succession.domain.render :as render]
            [succession.domain.salience :as salience]
            [succession.hook.common :as common]
            [succession.store.cards :as store-cards]))

(defn- tool-descriptor
  "Same format as post-tool-use — keeps card fingerprints substring-
   matchable across both hook events."
  [tool-name tool-input]
  (str "tool=" (or tool-name "?")
       (when tool-input
         (str ",input=" (pr-str tool-input)))))

(defn build-reminder
  "Pure: ranked salient cards → reminder markdown. Extracted for tests."
  [ranked]
  (render/salient-reminder ranked "**Salient identity — upcoming tool**"))

(defn run
  []
  (try
    (let [input        (common/read-input)
          project-root (common/project-root input)
          session      (or (:session_id input) "unknown")
          tool-name    (:tool_name input)
          tool-input   (:tool_input input)
          transcript   (:transcript_path input)
          now          (java.util.Date.)
          cfg          (common/load-config input)

          ;; Refresh gate — shared with PostToolUse via the same /tmp
          ;; state file keyed by session. Parallel tool batches dedup
          ;; because cur-bytes doesn't grow between the N PreToolUse
          ;; invocations fired before the tools run.
          prev-state   (common/read-refresh-state session)
          cur-bytes    (common/transcript-bytes transcript)
          emit?        (common/should-emit? prev-state cur-bytes (:refresh/gate cfg))]
      (when emit?
        (let [cards    (or (:cards (store-cards/read-promoted-snapshot project-root)) [])
              scored   (common/score-cards project-root cards cfg now)
              descriptor (tool-descriptor tool-name tool-input)
              situation  {:situation/text "before tool call"
                          :situation/tool-descriptor descriptor}
              ranked     (salience/rank scored situation cfg)]
          (when (seq ranked)
            (common/write-refresh-state! session
                                         {:emits           (inc (or (:emits prev-state) 0))
                                          :last-emit-bytes cur-bytes})
            (common/emit-additional-context! "PreToolUse" (build-reminder ranked))))))
    (catch Throwable t
      (binding [*out* *err*]
        (println "succession pre-tool-use error:" (.getMessage t)))))
  nil)
