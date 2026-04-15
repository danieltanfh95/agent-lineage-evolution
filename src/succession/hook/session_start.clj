(ns succession.hook.session-start
  "SessionStart hook — auditability + first-turn priming only.

   Finding 1 established that SessionStart `additionalContext` is near-
   inert for behavioral uplift. Do NOT mis-wire this as the delivery
   path; delivery happens through PostToolUse refresh.

   What SessionStart does, per plan §SessionStart:

     1. Auto-init: if `.succession/` is absent, create the store dirs
        and write the starter config.edn — supports the global-install
        flow where per-project setup happens on first session.
     2. Load the materialized `promoted.edn` snapshot.
     3. Check `staging/{other-sessions}/` for orphan deltas (a previous
        crashed session). If present, surface them in the returned
        additionalContext as a pending-reconciliation note — do NOT
        auto-promote, because that would bypass the PreCompact lock.
     4. Render the promoted cards as a markdown behavior tree via
        `domain/render/identity-tree`.
     5. Append the consult-skill footer so the agent knows how to pull
        on its identity mid-reasoning.
     6. Emit `{:hookSpecificOutput {:additionalContext <md>}}`.

   No LLM. Disk writes only on first-time auto-init."
  (:require [clojure.java.io :as io]
            [succession.cli.install :as install]
            [succession.config :as config]
            [succession.domain.render :as render]
            [succession.domain.rollup :as rollup]
            [succession.domain.weight :as weight]
            [succession.hook.common :as common]
            [succession.store.cards :as store-cards]
            [succession.store.observations :as store-obs]
            [succession.store.paths :as paths]
            [succession.store.sessions :as store-sessions]))

(def ^:private consult-skill-footer
  (str "---\n\n"
       "You can consult your identity via `succession consult \"<situation>\"` "
       "when uncertain or sensing contradiction. Consultation is a reflective "
       "second opinion against your own cards — not a performance metric. "
       "See the `succession-consult` skill for details."))

(defn- orphan-note
  "Short markdown blurb when orphan staging is detected."
  [orphans]
  (when (seq orphans)
    (str "\n\n> **Pending reconciliation:** "
         (count orphans)
         " earlier session(s) have staging that will be cleared on the next PreCompact.")))

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
          _            (when-not (.exists (io/file (paths/root project-root)))
                         (install/install-store-dirs! project-root)
                         (install/install-config!     project-root)
                         (when (get (config/load-config project-root) :auto-install/starter-pack true)
                           (install/install-starter-pack! project-root))
                         (binding [*out* *err*]
                           (println (str "succession: initialized store at " project-root))))
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
