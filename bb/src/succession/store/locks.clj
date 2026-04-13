(ns succession.store.locks
  "Advisory lock around the promotion path AND (since the async-lane
   refactor) around the drain worker.

   Two callers, same primitive:

   - PreCompact holds the promote lock so two concurrent promotions
     cannot race on the card tree.
   - worker/drain holds `.worker.lock` under `staging/jobs/` so at-most-
     one drain worker pulls jobs off the queue at a time.

   Both sites share the same create-if-not-exists semantics via
   `java.nio.file.Files/createFile` — atomic per the JDK javadoc so two
   concurrent callers cannot both succeed.

   The original `try-lock` / `release!` / `stale?` / `break-stale!`
   functions are unchanged 1-arg forms over `project-root`, pointing at
   `paths/promote-lock`. The new worker call-site uses the `*-at`
   variants which take an explicit lock-path. Keeping the promote-lock
   shims stable means `hook/pre_compact.clj` is untouched.

   Babashka does not expose `java.nio.channels.FileLock` methods
   (FileLockImpl methods are blocked by SCI), so we cannot use true
   OS-level advisory flock. This create-if-not-exists approach is
   cooperative: processes that use it coordinate; processes that
   bypass it are not prevented.

   Stale-lock recovery: the lock file stores the PID and creation
   timestamp. Staleness is judged off mtime — the worker heartbeats
   its lock file's mtime via `heartbeat!`, so a live worker is never
   mistakenly treated as stale by a concurrent hook checking
   `stale-at?`.

   Reference: `.plans/succession-identity-cycle.md` §Disk layout and
   the async-lane plan §Worker lifecycle & crash recovery."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [succession.store.paths :as paths])
  (:import (java.nio.file Files Path Paths)
           (java.nio.file.attribute FileAttribute)))

(defn- ^Path as-path [^String p]
  (Paths/get p (into-array String [])))

(defn- pid []
  (try (.pid (java.lang.ProcessHandle/current))
       (catch Throwable _ -1)))

(defn- host []
  (try (.getHostName (java.net.InetAddress/getLocalHost))
       (catch Throwable _ "unknown")))

;; ------------------------------------------------------------------
;; Low-level path-addressed primitive. The promote-lock shims below
;; and the worker's .worker.lock both route through this.
;; ------------------------------------------------------------------

(defn try-lock-at
  "Attempt to acquire a lock file at the explicit `path-str`. Returns
   `{:path <str> :acquired-at <Date>}` on success, or nil if the lock
   file already exists. Non-blocking.

   Atomically creates the file via `Files/createFile` — per the JDK
   javadoc, two concurrent callers cannot both succeed. On success,
   writes a small EDN body (pid, acquired-at, last-beat, host) for
   debugging and heartbeat tracking."
  [path-str]
  (let [_    (paths/ensure-dir! (.getParent (io/file path-str)))
        path (as-path path-str)]
    (try
      (Files/createFile path (into-array FileAttribute []))
      (let [now (java.util.Date.)]
        (spit path-str (pr-str {:pid         (pid)
                                :acquired-at now
                                :last-beat   now
                                :host        (host)}))
        {:path path-str :acquired-at now})
      (catch java.nio.file.FileAlreadyExistsException _ nil))))

(defn release!
  "Delete the lock file. Safe to call on nil (no-op) so callers can
   uniformly `(release! (try-lock ...))`."
  [handle]
  (when handle
    (try (Files/deleteIfExists (as-path (:path handle)))
         (catch Throwable _ nil))
    nil))

(defn heartbeat!
  "Update the lock file's body with a fresh `:last-beat` timestamp and
   touch its mtime. Called periodically by the long-running drain
   worker so stale-lock detection from concurrent hooks doesn't kick
   in while a legitimate worker is still alive. No-op if the lock
   file has been deleted out from under us — we return nil rather
   than re-creating it so a force-unlocked worker naturally exits on
   its next beat."
  [handle]
  (when handle
    (let [path-str (:path handle)
          f        (io/file path-str)]
      (when (.exists f)
        (try
          (let [existing (try (edn/read-string (slurp f)) (catch Throwable _ {}))
                now      (java.util.Date.)]
            (spit path-str (pr-str (assoc existing :last-beat now)))
            (.setLastModified f (System/currentTimeMillis)))
          (catch Throwable _ nil)))
      nil)))

(defn stale-at?
  "Is the lock at `path-str` older than `stale-after-seconds`? Returns
   false if no lock is held. Age is computed against mtime, which
   `heartbeat!` refreshes, so a live worker is never stale."
  [path-str stale-after-seconds]
  (let [f (io/file path-str)]
    (and (.exists f)
         (let [age-ms (- (System/currentTimeMillis) (.lastModified f))]
           (> age-ms (* 1000 stale-after-seconds))))))

(defn break-stale-at!
  "Delete a lock file by path. Dangerous — caller is responsible for
   confirming the owning process is actually dead. Returns true if
   the file was deleted."
  [path-str]
  (try
    (Files/deleteIfExists (as-path path-str))
    (catch Throwable _ false)))

;; ------------------------------------------------------------------
;; Promote-lock shims. Unchanged signatures; hook/pre_compact.clj
;; keeps calling these.
;; ------------------------------------------------------------------

(defn try-lock
  "Promote-lock shim: acquires `.succession/promote.lock` for
   `project-root`. See `try-lock-at` for the general form."
  [project-root]
  (try-lock-at (paths/promote-lock project-root)))

(defn stale?
  "Promote-lock shim: see `stale-at?`."
  [project-root stale-after-seconds]
  (stale-at? (paths/promote-lock project-root) stale-after-seconds))

(defn break-stale!
  "Promote-lock shim: see `break-stale-at!`."
  [project-root]
  (break-stale-at! (paths/promote-lock project-root)))

(defmacro with-lock
  "Run `body` with the promote lock held, or throw if the lock is
   already taken. Always releases in a finally block."
  [[handle-sym project-root] & body]
  `(let [~handle-sym (try-lock ~project-root)]
     (when-not ~handle-sym
       (throw (ex-info "promote lock is held by another process" {})))
     (try ~@body
          (finally (release! ~handle-sym)))))
