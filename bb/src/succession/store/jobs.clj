(ns succession.store.jobs
  "Filesystem-backed job queue for the async lane. One file = one job.

   Disk layout (under `<project-root>/.succession/staging/jobs/`):

     <iso-ts>-<uuid>.json           ; pending — visible to scanner
     .inflight/<iso-ts>-<uuid>.json  ; claimed by a drain worker
     dead/<iso-ts>-<uuid>.json       ; failed (sibling .error.edn holds cause)
     .worker.lock                    ; at-most-one drain worker marker

   Filename timestamp prefix is ISO-8601 basic (`20260411T143022123Z`)
   so lexicographic order == chronological order. Tie-breaking within
   the same millisecond is by UUID suffix, which gives a total order
   even under concurrent enqueuers.

   **Atomicity**: enqueue writes `<file>.tmp` then `Files/move` with
   `ATOMIC_MOVE`, so scanners never see a half-written JSON file.
   Claim also uses atomic move (jobs/ → .inflight/) so two workers
   cannot both claim the same file.

   Job shape (JSON on disk; round-tripped through cheshire so it's a
   plain map with string keys on read — the worker converts keyword
   on hand-off):

     {:job/id          \"uuid-str\"
      :job/type        \"judge\"          ; or \"llm-reconcile\"
      :job/enqueued-at \"2026-04-11T14:30:22.123Z\"
      :job/session     \"session-uuid\"
      :job/project-root \"/abs/path\"
      :job/payload     {...}
      :job/attempts    0}

   This namespace is pure I/O over that shape — sorting, idle
   detection, and classification live in `domain/queue.clj` so they
   can be unit-tested without touching disk.

   Reference: async-lane plan §Data shapes and §store/jobs.clj."
  (:require [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [succession.domain.queue :as queue]
            [succession.store.paths :as paths])
  (:import (java.nio.file Files NoSuchFileException Path Paths
                          StandardCopyOption)
           (java.time.format DateTimeFormatter)
           (java.util Date)))

;; ------------------------------------------------------------------
;; Filename helpers
;; ------------------------------------------------------------------

(def ^:private iso-basic
  "ISO-8601 basic (no separators) formatter, UTC. Lex sort == chrono."
  (-> (DateTimeFormatter/ofPattern "yyyyMMdd'T'HHmmssSSS'Z'")
      (.withZone (java.time.ZoneOffset/UTC))))

(defn- format-ts [^Date d]
  (.format iso-basic (.toInstant d)))

(defn- filename-for
  [^Date enqueued-at job-id]
  (str (format-ts enqueued-at) "-" job-id ".json"))

(defn- ^Path as-path [^String s]
  (Paths/get s (into-array String [])))

(defn- atomic-move!
  "Rename `src` → `dst` atomically. Falls back to a REPLACE_EXISTING
   move only if the first call fails with something other than
   `NoSuchFileException` — a missing source is a real failure that
   `claim!` wants to see, not a fallback signal. The specific
   AtomicMoveNotSupportedException is not whitelisted in babashka's
   SCI, so we detect the fallback case structurally. Parent of `dst`
   must exist."
  [^String src ^String dst]
  (try
    (Files/move (as-path src) (as-path dst)
                (into-array java.nio.file.CopyOption
                            [StandardCopyOption/ATOMIC_MOVE]))
    (catch NoSuchFileException e
      (throw e))
    (catch java.io.IOException _
      (Files/move (as-path src) (as-path dst)
                  (into-array java.nio.file.CopyOption
                              [StandardCopyOption/REPLACE_EXISTING])))))

;; ------------------------------------------------------------------
;; Enqueue
;; ------------------------------------------------------------------

(defn make-job
  "Build a job map from the minimal caller-supplied fields. Fills in
   `:job/id`, `:job/enqueued-at`, `:job/attempts`. Does not touch
   disk. The returned map is what `enqueue!` serializes."
  [{:keys [type session project-root payload]}]
  {:job/id           (str (random-uuid))
   :job/type         (some-> type name)
   :job/enqueued-at  (Date.)
   :job/session      session
   :job/project-root project-root
   :job/payload      payload
   :job/attempts     0})

(defn- write-json-atomic!
  "Write `obj` as JSON to `dest-path` via a sibling `.tmp` file and an
   atomic rename. Creates the parent dir if needed."
  [obj dest-path]
  (let [tmp    (str dest-path ".tmp")
        parent (.getParent (io/file dest-path))]
    (paths/ensure-dir! parent)
    (spit tmp (json/generate-string obj))
    (atomic-move! tmp dest-path)
    dest-path))

(defn enqueue!
  "Write a job to `jobs/<ts>-<id>.json`. Returns the absolute path of
   the final (non-.tmp) file. The caller is typically a hook, which
   returns immediately after enqueueing — the drain worker picks the
   file up on its next scan."
  [project-root job]
  (let [fname (filename-for (:job/enqueued-at job) (:job/id job))
        dest  (paths/job-file project-root fname)]
    (write-json-atomic! job dest)
    dest))

;; ------------------------------------------------------------------
;; Scanning / claiming
;; ------------------------------------------------------------------

(defn- pending-files
  "List `.json` files directly under `jobs-dir` (not recursing into
   `.inflight` or `dead`). Returns raw File objects, sorted by name
   so callers get FIFO order for free."
  [^java.io.File jobs-dir]
  (if-not (.exists jobs-dir)
    []
    (->> (.listFiles jobs-dir)
         (filter (fn [^java.io.File f]
                   (and (.isFile f)
                        (let [n (.getName f)]
                          (and (str/ends-with? n ".json")
                               (not (str/ends-with? n ".tmp"))
                               (not (str/starts-with? n "."))))))) ; defensive
         (sort-by #(.getName ^java.io.File %)))))

(defn- read-job-file
  "Parse a JSON job file into a map with keyword keys."
  [^java.io.File f]
  (try
    (let [raw (json/parse-string (slurp f) true)]
      (assoc raw :job/file (.getAbsolutePath f)
                 :job/filename (.getName f)))
    (catch Throwable _ nil)))

(defn list-pending
  "Return all pending jobs as parsed maps, in FIFO (filename-sorted)
   order. Each map has `:job/file` and `:job/filename` attached so
   the caller can later `claim!` it by filename. Unparseable files
   are silently skipped (they're most likely half-written .tmp files
   that slipped past the filter)."
  [project-root]
  (->> (pending-files (io/file (paths/jobs-dir project-root)))
       (keep read-job-file)
       vec))

(defn claim!
  "Atomically move `jobs/<filename>` → `.inflight/<filename>`. Returns
   the new absolute path on success, or nil if the file is already
   gone (another worker beat us, or the hook deleted it).

   The filename is preserved during the move so retries (via
   `sweep-stale-inflight!`) land back in the jobs dir with their
   original timestamp — the job never jumps the FIFO line."
  [project-root filename]
  (let [src (paths/job-file project-root filename)
        _   (paths/ensure-dir! (paths/jobs-inflight-dir project-root))
        dst (str (paths/jobs-inflight-dir project-root) "/" filename)]
    (try
      (atomic-move! src dst)
      ;; Touch mtime to NOW so `sweep-stale-inflight!` measures staleness
      ;; from claim time, not the original enqueue time that ATOMIC_MOVE
      ;; preserves. Without this, a long queue wait (> inflight-sweep-seconds
      ;; from enqueue) causes the sweep to move the file back while the
      ;; handler is still running, creating an infinite claim loop.
      (.setLastModified (io/file dst) (System/currentTimeMillis))
      dst
      (catch NoSuchFileException _ nil)
      (catch Throwable e (throw e)))))

(defn record-claim!
  "Read the inflight file at `inflight-path`, increment `:job/attempts`,
   append the current ISO-8601 timestamp to `:job/claim-timestamps`, and
   write back atomically. Returns the updated job map (keyword keys), or
   nil if the file disappeared between claim and read.

   Called immediately after a successful `claim!`. The data written here
   is what the circuit-breaker in `scan-and-claim!` reads to decide
   whether to dead-letter the job."
  [inflight-path]
  (try
    (let [job     (json/parse-string (slurp inflight-path) true)
          ts      (str (.truncatedTo (java.time.Instant/now)
                                     java.time.temporal.ChronoUnit/SECONDS))
          updated (-> job
                      (update :job/attempts (fnil inc 0))
                      (update :job/claim-timestamps (fnil conj []) ts))]
      (spit inflight-path (json/generate-string updated))
      updated)
    (catch Throwable _ nil)))

;; ------------------------------------------------------------------
;; Completion
;; ------------------------------------------------------------------

(defn complete!
  "Delete an inflight job file. Called by the worker after a
   successful handler run. Safe on a missing file."
  [project-root filename]
  (let [p (str (paths/jobs-inflight-dir project-root) "/" filename)]
    (try
      (Files/deleteIfExists (as-path p))
      (catch Throwable _ false))))

(defn dead-letter!
  "Move an inflight job file to `dead/<filename>` and write a sibling
   `<stem>.error.edn` capturing `{:ex-message :ex-class :at :job}`.
   The job map is passed in so we can include its payload in the
   error file without re-reading the file (which may already be
   gone)."
  [project-root filename job throwable]
  (let [src      (str (paths/jobs-inflight-dir project-root) "/" filename)
        dead-dir (paths/jobs-dead-dir project-root)
        _        (paths/ensure-dir! dead-dir)
        dst      (str dead-dir "/" filename)
        stem     (if (str/ends-with? filename ".json")
                   (subs filename 0 (- (count filename) 5))
                   filename)
        err-path (str dead-dir "/" stem ".error.edn")
        err-map  {:ex-message (some-> throwable .getMessage)
                  :ex-class   (some-> throwable class .getName)
                  :at         (Date.)
                  :trace      (queue/format-throwable-trace throwable)
                  :job        (dissoc job :job/file :job/filename)}]
    (try (atomic-move! src dst) (catch Throwable _ nil))
    (spit err-path (pr-str err-map))
    dst))

;; ------------------------------------------------------------------
;; Inflight sweep
;;
;; A worker that died mid-job leaves a file in `.inflight/`. The next
;; worker's first act is to move any such files older than
;; `stale-seconds` back to `jobs/` so they get retried in FIFO order.
;; This is the *only* retry path in the system — handler failures
;; go to dead-letter, not back on the queue.
;; ------------------------------------------------------------------

(defn sweep-stale-inflight!
  "Move `.inflight/*.json` files with mtime older than
   `stale-seconds` back to `jobs/`. Called once by a freshly-started
   drain worker. Returns the count of files recovered."
  [project-root stale-seconds]
  (let [inflight (io/file (paths/jobs-inflight-dir project-root))
        jobs-dir (paths/jobs-dir project-root)]
    (if-not (.exists inflight)
      0
      (let [now-ms (System/currentTimeMillis)
            cutoff (* 1000 stale-seconds)]
        (reduce
          (fn [n ^java.io.File f]
            (if (and (.isFile f)
                     (str/ends-with? (.getName f) ".json")
                     (> (- now-ms (.lastModified f)) cutoff))
              (let [dst (str jobs-dir "/" (.getName f))]
                (try
                  (atomic-move! (.getAbsolutePath f) dst)
                  (inc n)
                  (catch Throwable _ n)))
              n))
          0
          (.listFiles inflight))))))

(defn count-pending
  "Fast count of pending jobs (no parsing). Used by the worker's
   idle-watcher loop without allocating the full list."
  [project-root]
  (count (pending-files (io/file (paths/jobs-dir project-root)))))

(defn count-inflight
  "Fast count of inflight jobs, for the idle-watcher."
  [project-root]
  (let [d (io/file (paths/jobs-inflight-dir project-root))]
    (if-not (.exists d)
      0
      (count (filter (fn [^java.io.File f]
                       (and (.isFile f)
                            (str/ends-with? (.getName f) ".json")))
                     (.listFiles d))))))

;; ------------------------------------------------------------------
;; Dead-letter inspection / recovery
;;
;; The drain worker writes failed jobs to `dead/<filename>.json`
;; alongside `dead/<stem>.error.edn`. The CLI surfaces under
;; `bb succession queue` need to enumerate, requeue, and prune those
;; pairs without dipping into filesystem internals.
;; ------------------------------------------------------------------

(defn- dead-json-files
  [project-root]
  (let [d (io/file (paths/jobs-dead-dir project-root))]
    (if-not (.exists d)
      []
      (->> (.listFiles d)
           (filter (fn [^java.io.File f]
                     (and (.isFile f)
                          (str/ends-with? (.getName f) ".json"))))
           (sort-by #(.getName ^java.io.File %))
           vec))))

(defn- read-error-sidecar
  "Read the `<stem>.error.edn` sibling for a dead job file. Returns nil
   if missing or unparseable — the EDN is recovery metadata, not the
   job itself, so a corrupted sidecar should never break enumeration."
  [^java.io.File job-file]
  (let [name     (.getName job-file)
        stem     (if (str/ends-with? name ".json")
                   (subs name 0 (- (count name) 5))
                   name)
        err-file (io/file (.getParentFile job-file) (str stem ".error.edn"))]
    (when (.exists err-file)
      (try (edn/read-string {:readers {}} (slurp err-file))
           (catch Throwable _ nil)))))

(defn list-dead
  "Return one map per dead-lettered job. Each entry combines the job
   payload (read fresh from `dead/<filename>.json`) with the sibling
   `<stem>.error.edn` so the CLI can render `filename | type | session
   | message` without two reads.

   Shape:
     {:job/filename  \"20260411T000001000Z-uuid.json\"
      :job/type      \"judge\"
      :job/session   \"...\"
      :job/at        #inst \"...\"     ; from the sidecar (failure time)
      :error/message \"...\"
      :error/class   \"...\"
      :error/trace   \"...\"           ; nil for jobs that died before
                                       ; the trace-capture fix landed
      :job/payload   {...}}"
  [project-root]
  (->> (dead-json-files project-root)
       (mapv (fn [^java.io.File f]
               (let [job (or (read-job-file f) {})
                     err (read-error-sidecar f)]
                 {:job/filename  (.getName f)
                  :job/type      (:job/type job)
                  :job/session   (:job/session job)
                  :job/at        (:at err)
                  :error/message (:ex-message err)
                  :error/class   (:ex-class err)
                  :error/trace   (:trace err)
                  :job/payload   (:job/payload job)})))))

(defn count-dead
  "Fast count of dead-lettered jobs, parallel to count-pending /
   count-inflight."
  [project-root]
  (count (dead-json-files project-root)))

(defn requeue!
  "Move `dead/<filename>.json` back into `jobs/<filename>` and delete
   the sibling `<stem>.error.edn`. Filename is preserved so the
   re-queued job sorts in its original FIFO position rather than
   jumping to the head.

   Returns the new `jobs/` path on success, nil if the dead file does
   not exist (e.g. already requeued or cleared by another caller)."
  [project-root filename]
  (let [src      (str (paths/jobs-dead-dir project-root) "/" filename)
        src-file (io/file src)]
    (when (.exists src-file)
      (let [_   (paths/ensure-dir! (paths/jobs-dir project-root))
            dst (paths/job-file project-root filename)
            stem (if (str/ends-with? filename ".json")
                   (subs filename 0 (- (count filename) 5))
                   filename)
            err  (io/file (paths/jobs-dead-dir project-root)
                          (str stem ".error.edn"))]
        (atomic-move! src dst)
        (try (Files/deleteIfExists (as-path (.getAbsolutePath err)))
             (catch Throwable _ nil))
        dst))))

(defn requeue-all!
  "Requeue every dead-lettered job. Returns the count moved. Used by
   `bb succession queue requeue --all`."
  [project-root]
  (reduce (fn [n ^java.io.File f]
            (if (requeue! project-root (.getName f))
              (inc n)
              n))
          0
          (dead-json-files project-root)))

(defn clear-dead!
  "Delete dead-letter pairs (the `.json` and the sibling `.error.edn`).

   When `older-than-ms` is non-nil, only remove pairs whose `.json`
   mtime is older than that many milliseconds. Pass nil (or zero) to
   wipe everything. Returns the count of `.json` files deleted."
  [project-root older-than-ms]
  (let [now-ms (System/currentTimeMillis)
        cutoff (or older-than-ms 0)]
    (reduce
      (fn [n ^java.io.File f]
        (if (or (zero? cutoff)
                (> (- now-ms (.lastModified f)) cutoff))
          (let [name (.getName f)
                stem (if (str/ends-with? name ".json")
                       (subs name 0 (- (count name) 5))
                       name)
                err  (io/file (.getParentFile f) (str stem ".error.edn"))]
            (try (.delete f) (catch Throwable _ nil))
            (try (.delete err) (catch Throwable _ nil))
            (inc n))
          n))
      0
      (dead-json-files project-root))))
