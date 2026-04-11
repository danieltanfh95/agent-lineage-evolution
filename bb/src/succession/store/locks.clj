(ns succession.identity.store.locks
  "Advisory lock around the promotion path.

   Only one PreCompact may promote at a time (otherwise two concurrent
   promotions could race on the card tree and produce inconsistent
   `promoted.edn`). Everything else — observation writes, staging
   appends, judge verdict writes — is safe without locks because each
   lives in its own file.

   Implementation: `java.nio.file.Files/createFile` with `CREATE_NEW`
   semantics. If the file already exists, creation fails and `try-lock`
   returns nil. On release, the file is deleted.

   Babashka does not expose `java.nio.channels.FileLock` methods
   (FileLockImpl methods are blocked by SCI), so we cannot use true
   OS-level advisory flock. This create-if-not-exists approach is
   cooperative: processes that use it coordinate; processes that
   bypass it are not prevented. For our single-project sidecar that
   is sufficient — the only contender is another instance of this
   same code.

   Stale-lock recovery: the lock file stores the PID and creation
   timestamp. A process finding a lock file older than
   `stale-after-seconds` (default 300) can clean it up. Not auto-
   cleaned here — callers decide.

   Reference: `.plans/succession-identity-cycle.md` §Disk layout (lock
   file entry) and §PreCompact (promotion is the only locked step)."
  (:require [clojure.java.io :as io]
            [succession.identity.store.paths :as paths])
  (:import (java.nio.file Files Path Paths StandardOpenOption
                          NoSuchFileException)
           (java.nio.file.attribute FileAttribute)))

(defn- ^Path as-path [^String p]
  (Paths/get p (into-array String [])))

(defn- pid []
  (try (.pid (java.lang.ProcessHandle/current))
       (catch Throwable _ -1)))

(defn try-lock
  "Attempt to acquire the promote lock. Returns a handle map
   `{:path <str> :acquired-at <Date>}` on success, or nil if the lock
   file already exists. Non-blocking — callers decide whether to spin,
   defer, or escalate.

   Behind the scenes: atomically creates the lock file via
   `Files/createFile`. That call is atomic per the JDK javadoc —
   two concurrent callers cannot both succeed. On success, the PID
   and acquire time are written to the file for debugging."
  [project-root]
  (let [path-str (paths/promote-lock project-root)
        _        (paths/ensure-dir! (.getParent (io/file path-str)))
        path     (as-path path-str)]
    (try
      (Files/createFile path (into-array FileAttribute []))
      (let [now (java.util.Date.)]
        (spit path-str (pr-str {:pid (pid)
                                :acquired-at now
                                :host (try (.getHostName (java.net.InetAddress/getLocalHost))
                                           (catch Throwable _ "unknown"))}))
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

(defn stale?
  "Is the current lock older than `stale-after-seconds`? Returns false
   if no lock is held or the lock file cannot be read."
  [project-root stale-after-seconds]
  (let [path-str (paths/promote-lock project-root)
        f        (io/file path-str)]
    (and (.exists f)
         (try
           (let [info     (read-string (slurp f))
                 acquired ^java.util.Date (:acquired-at info)
                 age-ms   (- (System/currentTimeMillis) (.getTime acquired))]
             (> age-ms (* 1000 stale-after-seconds)))
           (catch Throwable _ false)))))

(defn break-stale!
  "Delete a stale lock file. Dangerous — caller is responsible for
   confirming the owning process is actually dead. Returns true if
   the file was deleted."
  [project-root]
  (try
    (Files/deleteIfExists (as-path (paths/promote-lock project-root)))
    (catch Throwable _ false)))

(defmacro with-lock
  "Run `body` with the promote lock held, or throw if the lock is
   already taken. Always releases in a finally block."
  [[handle-sym project-root] & body]
  `(let [~handle-sym (try-lock ~project-root)]
     (when-not ~handle-sym
       (throw (ex-info "promote lock is held by another process" {})))
     (try ~@body
          (finally (release! ~handle-sym)))))
