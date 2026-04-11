(ns succession.domain.queue
  "Pure domain functions for the async job queue. Every function here
   takes plain values and returns plain values — no I/O, no channels,
   no clocks beyond what's passed in. The orchestration layer
   (`worker/drain`) threads these into a core.async pipeline; the
   store layer (`store/jobs`) threads them into disk operations.

   The point of this split is testability: sorting policy, idle
   detection, and result classification can be exercised with plain
   vectors in a unit test without tmp dirs or bb subprocess forks.

   Reference: async-lane plan §Layer split, §domain/queue.clj."
  (:require [clojure.stacktrace :as stacktrace])
  (:import (java.util Date)))

(defn format-throwable-trace
  "Render a throwable's stack trace to a multi-line string. Pure derivation
   — `with-out-str` captures the print to a local buffer, so this stays
   I/O-free and safe to call from any layer.

   Centralized here so the in-memory result map and the on-disk
   `.error.edn` agree on the trace format. Without this, dead-letter
   files were silently missing the stack and the failure was invisible
   even after rotting on disk for a full dev day."
  [throwable]
  (when throwable
    (with-out-str (stacktrace/print-stack-trace throwable))))

;; ------------------------------------------------------------------
;; FIFO sorting
;; ------------------------------------------------------------------

(defn sort-jobs
  "Return `jobs` sorted in FIFO order. The sort key is the filename
   attached by `store/jobs/list-pending`, which is an ISO-8601 basic
   timestamp followed by a UUID — lexicographic sort is chronological
   and UUID suffixes give a total order for jobs enqueued in the
   same millisecond.

   This function is the whole sorting policy for the queue. There is
   no type-priority axis — judge and llm-reconcile jobs interleave
   purely by enqueue time because later observations reference earlier
   card state and later contradictions may depend on earlier
   resolutions. Reordering by type would introduce subtle causality
   bugs."
  [jobs]
  (vec (sort-by :job/filename jobs)))

;; ------------------------------------------------------------------
;; Idle detection — the predicate the worker uses to decide when to
;; close its input channel and exit.
;; ------------------------------------------------------------------

(defn idle?
  "Given a queue-state snapshot and the effective worker config,
   decide whether the worker should stop. A worker is idle when:

   - no pending jobs are waiting in `jobs/`
   - no inflight jobs are being processed
   - at least `:idle-timeout-seconds` has elapsed since the last
     moment either of those counts was positive (tracked as
     `:queue/last-activity-at` by the caller)

   `queue-state` must supply `:queue/pending-count`,
   `:queue/inflight-count`, `:queue/last-activity-at`, and
   `:queue/now` (all plain values). `config` is the `:worker/async`
   section of the effective config."
  [{:keys [queue/pending-count queue/inflight-count
           queue/last-activity-at queue/now]}
   {:keys [idle-timeout-seconds]
    :or   {idle-timeout-seconds 10}}]
  (and (zero? (or pending-count 0))
       (zero? (or inflight-count 0))
       (some? last-activity-at)
       (let [age-ms (- (.getTime ^Date now)
                       (.getTime ^Date last-activity-at))]
         (>= age-ms (* 1000 idle-timeout-seconds)))))

;; ------------------------------------------------------------------
;; Result construction + classification
;; ------------------------------------------------------------------

(defn job->result
  "Build a Job-result map from a completed handler call. `throwable`
   is nil on success, or the exception caught by the pipeline on
   failure. `side-effects` is an optional seq of
   `{:kind ... :path ...}` records the handler wants to report."
  ([job throwable started-at finished-at]
   (job->result job throwable started-at finished-at nil))
  ([job throwable ^Date started-at ^Date finished-at side-effects]
   {:result/job-id       (:job/id job)
    :result/job-filename (:job/filename job)
    :result/status       (if throwable :error :ok)
    :result/started-at   started-at
    :result/finished-at  finished-at
    :result/error        (when throwable
                           {:class   (some-> throwable class .getName)
                            :message (some-> throwable .getMessage)
                            :trace   (format-throwable-trace throwable)})
    :result/side-effects (vec side-effects)}))

(defn classify-result
  "Return `:ok` or `:error` for a result map. Trivial but centralized
   so the drain layer has a single point of truth when deciding
   between `complete!` and `dead-letter!`."
  [result]
  (or (:result/status result) :error))
