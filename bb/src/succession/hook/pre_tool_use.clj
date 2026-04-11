(ns succession.identity.hook.pre-tool-use
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
   step. Only difference: PreToolUse has no refresh gate — the agent
   about to call a tool is exactly the moment we want to show salient
   cards, regardless of how recently we last surfaced them.

   Reference: `.plans/succession-identity-cycle.md` §PreToolUse."
  (:require [succession.identity.domain.render :as render]
            [succession.identity.domain.salience :as salience]
            [succession.identity.hook.common :as common]
            [succession.identity.store.cards :as store-cards]))

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
          tool-name    (:tool_name input)
          tool-input   (:tool_input input)
          now          (java.util.Date.)
          cfg          (common/load-config input)
          cards        (or (:cards (store-cards/read-promoted-snapshot project-root)) [])
          scored       (common/score-cards project-root cards cfg now)
          descriptor   (tool-descriptor tool-name tool-input)
          situation    {:situation/text "before tool call"
                        :situation/tool-descriptor descriptor}
          ranked       (salience/rank scored situation cfg)]
      (when (seq ranked)
        (common/emit-additional-context! "PreToolUse" (build-reminder ranked))))
    (catch Throwable t
      (binding [*out* *err*]
        (println "succession.identity pre-tool-use error:" (.getMessage t)))))
  nil)
