(ns succession.activity
  "Project-scoped activity logging: operational events like session_start,
   correction_tier1, extraction, etc. Written to
   cwd/.succession/log/succession-activity.jsonl.

   Distinct from the global meta-cognition log (effectiveness.clj) which
   tracks rule lifecycle events."
  (:require [cheshire.core :as json]
            [babashka.fs :as fs]))

(defn activity-log-path
  "Returns path to the project-scoped activity log."
  [cwd]
  (str cwd "/.succession/log/succession-activity.jsonl"))

(def ^:private rotation-threshold
  "1MB — rotate when activity log exceeds this size."
  1048576)

(defn rotate-log-if-needed!
  "If succession-activity.jsonl > 1MB, rename to .jsonl.1"
  [cwd]
  (let [log-file (activity-log-path cwd)]
    (when (fs/exists? log-file)
      (when (> (fs/size log-file) rotation-threshold)
        (fs/move log-file (str log-file ".1") {:replace-existing true})))))

(defn log-activity-event!
  "Append a structured event to the project-scoped activity log.
   event-type: string like 'session_start', 'correction_tier1', etc.
   cwd: project directory (determines log location)
   session-id: current session ID
   attrs: map of additional fields"
  [event-type cwd session-id attrs]
  (let [log-file (activity-log-path cwd)]
    (fs/create-dirs (fs/parent log-file))
    (spit log-file
          (str (json/generate-string
                (merge {:timestamp (str (java.time.Instant/now))
                        :session session-id
                        :event event-type}
                       attrs))
               "\n")
          :append true)))
