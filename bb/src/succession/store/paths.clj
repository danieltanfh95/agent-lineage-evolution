(ns succession.store.paths
  "Canonical disk paths for the identity store. Every other store
   namespace derives its paths from here so the disk layout can be
   moved with one edit.

   Reference: `.plans/succession-identity-cycle.md` §Disk layout.

   Layout under `<root>/.succession/`:

     identity/promoted/{tier}/{card-id}.md    ; canonical identity
     promoted.edn                              ; fast-read materialized snapshot
     staging/{session-id}/deltas.jsonl         ; intra-session append-only
     staging/{session-id}/snapshot.edn         ; materialized staging view
     staging/{session-id}/contradictions.jsonl ; pure-detector output
     observations/{session-id}/{ts}-{uuid}.edn ; one file per observation
     contradictions/{contradiction-id}.edn     ; canonical contradiction records
     judge/{session-id}/verdicts.jsonl         ; raw judge verdicts
     archive/{pre-compact-ts}/promoted/        ; timestamped promoted snapshots
     escalations.jsonl                         ; user-facing escalations
     promote.lock                              ; advisory flock"
  (:require [clojure.java.io :as io]))

(def ^:private tier-dir-names
  "Romanized directory names per plan §Disk layout."
  {:principle "principle"
   :rule      "rule"
   :ethic     "ethic"})

(defn- join [& parts] (.getPath ^java.io.File (apply io/file parts)))

(defn root
  "Return the `.succession/` root under `project-root`."
  [project-root]
  (join project-root ".succession"))

(defn promoted-dir
  "Directory holding canonical identity cards grouped by tier."
  [project-root]
  (join (root project-root) "identity" "promoted"))

(defn tier-dir
  [project-root tier]
  (join (promoted-dir project-root) (get tier-dir-names tier)))

(defn card-file
  "Absolute path to a card file given tier + id."
  [project-root tier card-id]
  (join (tier-dir project-root tier) (str card-id ".md")))

(defn promoted-snapshot
  "Path to the materialized `promoted.edn` fast-read snapshot."
  [project-root]
  (join (root project-root) "promoted.edn"))

(defn staging-dir
  ([project-root] (join (root project-root) "staging"))
  ([project-root session-id] (join (staging-dir project-root) session-id)))

(defn staging-deltas
  [project-root session-id]
  (join (staging-dir project-root session-id) "deltas.jsonl"))

(defn staging-snapshot
  [project-root session-id]
  (join (staging-dir project-root session-id) "snapshot.edn"))

(defn staging-contradictions
  [project-root session-id]
  (join (staging-dir project-root session-id) "contradictions.jsonl"))

(defn observations-dir
  ([project-root] (join (root project-root) "observations"))
  ([project-root session-id] (join (observations-dir project-root) session-id)))

(defn observation-file
  "Observation file path. `ts` is an ISO-like string safe for filenames;
   `uuid` disambiguates concurrent writes within the same millisecond."
  [project-root session-id ts uuid]
  (join (observations-dir project-root session-id)
        (str ts "-" uuid ".edn")))

(defn contradictions-dir
  [project-root]
  (join (root project-root) "contradictions"))

(defn contradiction-file
  [project-root contradiction-id]
  (join (contradictions-dir project-root) (str contradiction-id ".edn")))

(defn judge-dir
  ([project-root] (join (root project-root) "judge"))
  ([project-root session-id] (join (judge-dir project-root) session-id)))

(defn judge-verdicts
  [project-root session-id]
  (join (judge-dir project-root session-id) "verdicts.jsonl"))

(defn archive-dir
  ([project-root] (join (root project-root) "archive"))
  ([project-root ts] (join (archive-dir project-root) ts)))

(defn escalations-log
  [project-root]
  (join (root project-root) "escalations.jsonl"))

(defn promote-lock
  [project-root]
  (join (root project-root) "promote.lock"))

;; ------------------------------------------------------------------
;; Async job queue (store/jobs + worker/drain). Layout:
;;
;;   staging/jobs/<ts>-<uuid>.json           ; pending
;;   staging/jobs/.inflight/<ts>-<uuid>.json  ; claimed by a worker
;;   staging/jobs/dead/<ts>-<uuid>.json       ; failed (with .error.edn)
;;   staging/jobs/.worker.lock                ; at-most-one worker
;; ------------------------------------------------------------------

(defn jobs-dir
  "Pending job directory. Workers list this to find work."
  [project-root]
  (join (staging-dir project-root) "jobs"))

(defn job-file
  "Absolute path to a pending job file given its filename (no
   directory components)."
  [project-root filename]
  (join (jobs-dir project-root) filename))

(defn jobs-inflight-dir
  "Directory where claimed jobs live while a worker is processing them."
  [project-root]
  (join (jobs-dir project-root) ".inflight"))

(defn jobs-dead-dir
  "Dead-letter directory for jobs that failed processing."
  [project-root]
  (join (jobs-dir project-root) "dead"))

(defn jobs-worker-lock
  "Lock file enforcing at-most-one drain worker per project-root.
   Intentionally sits *inside* the jobs dir so the lock and the queue
   share a fate — deleting the dir clears the lock too."
  [project-root]
  (join (jobs-dir project-root) ".worker.lock"))

(defn ensure-dir!
  "Create a directory (including parents) if it does not exist. Returns
   the path. Idempotent; safe to call on a fresh or existing tree."
  [path]
  (let [f (io/file path)]
    (when-not (.exists f) (.mkdirs f))
    path))
