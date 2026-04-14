(ns succession.cli.compact
  "`bb -m succession.core compact` — manual promotion flush.

   Runs the full pre-compact promotion pipeline on demand, without
   waiting for the context window to fill. Semantically identical to
   what the PreCompact hook does when Claude Code compacts naturally.

   The synthetic session trick: promote! requires a session arg to
   distinguish the 'current' session (cleared normally) from orphans
   (also cleared). Passing a UUID that doesn't exist on disk means
   orphan-staging returns ALL real sessions for cleanup, and
   clear-session! for the synthetic ID is a no-op."
  (:require [succession.config :as config]
            [succession.hook.pre-compact :as pre-compact]))

(def ^:private synthetic-session
  "A session-id guaranteed not to exist on disk. Passed to promote! so
   orphan-staging returns *all* real sessions for cleanup."
  "00000000-0000-0000-0000-000000000000")

(defn run [project-root _args]
  (try
    (let [now (java.util.Date.)
          cfg (config/load-config project-root)]
      (pre-compact/promote! project-root synthetic-session now cfg)
      (println "compact: promotion complete, all staged sessions cleared")
      0)
    (catch Throwable t
      (binding [*out* *err*]
        (println "compact error:" (.getMessage t)))
      1)))
