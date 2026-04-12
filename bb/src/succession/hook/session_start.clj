(ns succession.hook.session-start
  "SessionStart hook — auditability + first-turn priming only.

   Finding 1 established that SessionStart `additionalContext` is near-
   inert for behavioral uplift. Do NOT mis-wire this as the delivery
   path; delivery happens through PostToolUse refresh.

   What SessionStart does, per plan §SessionStart:

     1. Load the materialized `promoted.edn` snapshot.
     2. Check `staging/{other-sessions}/` for orphan deltas (a previous
        crashed session). If present, surface them in the returned
        additionalContext as a pending-reconciliation note — do NOT
        auto-promote, because that would bypass the PreCompact lock.
     3. Render the promoted cards as a markdown behavior tree via
        `domain/render/identity-tree`.
     4. Append the consult-skill footer so the agent knows how to pull
        on its identity mid-reasoning.
     5. Emit `{:hookSpecificOutput {:additionalContext <md>}}`.

   No LLM. No disk writes (orphan handling is flagged, not applied)."
  (:require [succession.domain.render :as render]
            [succession.domain.rollup :as rollup]
            [succession.domain.weight :as weight]
            [succession.hook.common :as common]
            [succession.store.cards :as store-cards]
            [succession.store.observations :as store-obs]
            [succession.store.sessions :as store-sessions]))

(def ^:private consult-skill-footer
  (str "---\n\n"
       "You can consult your identity via `bb succession consult \"<situation>\"` "
       "when uncertain or sensing contradiction. Consultation is a reflective "
       "second opinion against your own cards — not a performance metric. "
       "See the `succession-consult` skill for details."))

(defn- orphan-note
  "Short markdown blurb when orphan staging is detected. The agent sees
   this at SessionStart once so it can choose to trigger reconciliation
   (e.g. by running `bb succession replay ...` or letting PreCompact
   handle the promotion on the next compaction)."
  [orphans]
  (when (seq orphans)
    (str "\n\n> **Pending reconciliation:** staging deltas from "
         (count orphans)
         " earlier session(s) have not yet been promoted: "
         (clojure.string/join ", " (map pr-str orphans))
         ". They will land on the next PreCompact.")))

(defn build-context
  "Pure: take (cards, scored, orphans) and produce the additionalContext
   markdown. Extracted so tests can exercise rendering without touching
   disk."
  [scored-cards orphans]
  (str (render/identity-tree
         scored-cards
         {:footer consult-skill-footer})
       (or (orphan-note orphans) "")))

(defn run
  []
  (try
    (let [input        (common/read-input)
          project-root (common/project-root input)
          current-sess (or (:session_id input) "unknown")
          now          (java.util.Date.)
          cfg          (common/load-config input)
          snapshot     (store-cards/read-promoted-snapshot project-root)
          cards        (or (:cards snapshot) [])
          scored       (common/score-cards project-root cards cfg now)
          orphans      (store-sessions/orphan-staging project-root current-sess)
          context      (build-context scored orphans)]
      (common/emit-additional-context! "SessionStart" context))
    (catch Throwable t
      (binding [*out* *err*]
        (println "succession session-start error:" (.getMessage t)))))
  nil)
