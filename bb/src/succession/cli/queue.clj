(ns succession.cli.queue
  "`bb -m succession.core queue <op>` — inspect and recover the async
   job queue.

   The async judge lane writes files under
   `.succession/staging/jobs/`. Until this CLI landed, there was no
   surface for answering operator questions like:

     - how many jobs are pending / inflight / dead?
     - what failed, and why?
     - can I replay the dead ones once the bug is fixed?

   Those are the gaps this namespace fills. It is intentionally
   minimal — no subcommand here does anything the operator can't do
   by hand, it just gives them a one-liner.

   Subcommands:

     status                         – summary line + counters
     list-dead                      – tabular list of dead-lettered jobs
     requeue <filename | --all>     – move dead → pending (preserves ts)
     clear-dead [--older-than Nd]   – delete dead pairs

   Output is plain text to stdout; errors go to stderr; exit code 0
   on success, 1 on invalid usage or fatal error.

   Reference: post-mortem of 2026-04-11 — 17 judge jobs dead-lettered
   silently, discovered only by chance while answering an unrelated
   operator question."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [succession.store.jobs :as store-jobs]
            [succession.store.paths :as paths]))

;; ------------------------------------------------------------------
;; Helpers
;; ------------------------------------------------------------------

(defn- err!
  "Print a message to stderr and return 1 for caller to propagate as
   the process exit code."
  [msg]
  (binding [*out* *err*] (println msg))
  1)

(defn- worker-lock-state
  "Describe the state of `.worker.lock`. Returns a short string that
   the status subcommand embeds in its one-liner. We don't check
   staleness (that's the drain worker's job) — we just report on
   presence."
  [project-root]
  (let [f (io/file (paths/jobs-worker-lock project-root))]
    (if (.exists f)
      (format "locked (mtime %ds ago)"
              (long (/ (- (System/currentTimeMillis) (.lastModified f))
                       1000)))
      "unlocked")))

;; ------------------------------------------------------------------
;; status
;; ------------------------------------------------------------------

(defn run-status
  "`queue status` — one-line counters + worker-lock state + recent log."
  [project-root]
  (let [pending  (store-jobs/count-pending project-root)
        inflight (store-jobs/count-inflight project-root)
        dead     (store-jobs/count-dead project-root)
        log-path (paths/jobs-worker-log project-root)
        log-file (io/file log-path)]
    (println
      (format "succession queue: %d pending · %d inflight · %d dead · %s"
              pending inflight dead (worker-lock-state project-root)))
    (when (pos? dead)
      (println)
      (println "Run `bb succession queue list-dead` to inspect failed jobs."))
    (when (.exists log-file)
      (let [lines   (str/split-lines (slurp log-path))
            last-20 (take-last 20 lines)]
        (println)
        (println "recent worker log:")
        (doseq [line last-20]
          (println (str "  " line)))))
    0))

;; ------------------------------------------------------------------
;; list-dead
;; ------------------------------------------------------------------

(defn- truncate
  "Trim a string to `n` characters with an ellipsis, preserving
   information density for the list-dead table."
  [s n]
  (let [s (or s "")]
    (if (<= (count s) n)
      s
      (str (subs s 0 (max 0 (- n 1))) "…"))))

(defn run-list-dead
  "`queue list-dead` — filename / type / session / ex-message per row."
  [project-root]
  (let [dead (store-jobs/list-dead project-root)]
    (if (empty? dead)
      (do (println "(no dead-lettered jobs)")
          0)
      (do
        (println
          (format "%-48s  %-12s  %-40s  %s"
                  "filename" "type" "session" "error"))
        (println (apply str (repeat 140 "-")))
        (doseq [d dead]
          (println
            (format "%-48s  %-12s  %-40s  %s"
                    (truncate (:job/filename d) 48)
                    (truncate (or (:job/type d) "?") 12)
                    (truncate (or (:job/session d) "?") 40)
                    (truncate (or (:error/message d) "") 60))))
        0))))

;; ------------------------------------------------------------------
;; requeue
;; ------------------------------------------------------------------

(defn run-requeue
  "`queue requeue <filename>` — move one dead job back to pending.
   `queue requeue --all` — move every dead job back.

   Filename preservation is deliberate: a re-queued job lands in its
   original FIFO position rather than jumping to the head, so the
   replay order matches enqueue order even after a recovery cycle."
  [project-root args]
  (let [target (first args)]
    (cond
      (str/blank? target)
      (err! "usage: bb succession queue requeue <filename> | --all")

      (= target "--all")
      (let [n (store-jobs/requeue-all! project-root)]
        (println (format "requeued %d dead-lettered job(s)" n))
        0)

      :else
      (if-let [_ (store-jobs/requeue! project-root target)]
        (do (println (format "requeued %s" target)) 0)
        (err! (format "no dead job named %s" target))))))

;; ------------------------------------------------------------------
;; clear-dead
;; ------------------------------------------------------------------

(defn- parse-duration
  "Parse a simple duration string like `7d`, `12h`, `30m` into
   milliseconds. Returns nil on parse failure so the caller can exit
   with a usage error."
  [s]
  (when (and s (re-matches #"^(\d+)([smhd])$" s))
    (let [[_ n unit] (re-matches #"^(\d+)([smhd])$" s)
          n (Long/parseLong n)]
      (* n (case unit
             "s" 1000
             "m" (* 60 1000)
             "h" (* 60 60 1000)
             "d" (* 24 60 60 1000))))))

(defn run-clear-dead
  "`queue clear-dead` — delete every dead pair.
   `queue clear-dead --older-than 7d` — only delete pairs older than
   the cutoff."
  [project-root args]
  (let [args (vec args)
        older (when (= (first args) "--older-than")
                (parse-duration (second args)))]
    (if (and (= (first args) "--older-than") (nil? older))
      (err! "usage: bb succession queue clear-dead [--older-than <N(s|m|h|d)>]")
      (let [n (store-jobs/clear-dead! project-root older)]
        (println (format "deleted %d dead-lettered pair(s)" n))
        0))))

;; ------------------------------------------------------------------
;; Dispatch
;; ------------------------------------------------------------------

(defn run
  [project-root args]
  (let [[op & rest-args] args]
    (case op
      "status"     (run-status project-root)
      "list-dead"  (run-list-dead project-root)
      "requeue"    (run-requeue project-root rest-args)
      "clear-dead" (run-clear-dead project-root rest-args)
      (err! (str "usage: bb succession queue <status|list-dead|requeue|clear-dead>"
                 (when op (str "\n  unknown subcommand: " op)))))))
