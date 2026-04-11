(ns succession.identity.store.sessions
  "Session-level queries over the staging/observations/ trees.

   Two jobs:

   1. **Enumerate sessions** that have any staging state on disk. Used
      by SessionStart to find orphan staging from a prior crashed or
      uncleanly-closed session.

   2. **Detect orphan staging**: a session directory that still exists
      in `.succession/staging/` but is not the currently starting
      session. Those deltas either need to be promoted (merge into
      canonical) or flagged as pending reconciliation."
  (:require [clojure.java.io :as io]
            [succession.identity.store.paths :as paths]))

(defn staged-sessions
  "Return a seq of session-ids that have a staging directory on disk.
   Empty if there is no staging tree at all."
  [project-root]
  (let [root (io/file (paths/staging-dir project-root))]
    (if-not (.exists root)
      []
      (->> (.listFiles root)
           (filter (fn [^java.io.File f] (.isDirectory f)))
           (map (fn [^java.io.File f] (.getName f)))
           vec))))

(defn observed-sessions
  "Return session-ids that have an observations directory."
  [project-root]
  (let [root (io/file (paths/observations-dir project-root))]
    (if-not (.exists root)
      []
      (->> (.listFiles root)
           (filter (fn [^java.io.File f] (.isDirectory f)))
           (map (fn [^java.io.File f] (.getName f)))
           vec))))

(defn orphan-staging
  "Return session-ids with staging state that are NOT the
   currently-starting session. These are candidates for promotion at
   SessionStart (or flagging as pending)."
  [project-root current-session-id]
  (->> (staged-sessions project-root)
       (remove #(= % current-session-id))
       vec))

(defn has-staging?
  "True if a session directory already exists in staging."
  [project-root session-id]
  (.exists (io/file (paths/staging-dir project-root session-id))))
