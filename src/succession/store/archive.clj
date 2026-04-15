(ns succession.store.archive
  "Timestamped snapshots of `identity/promoted/` taken at each
   PreCompact promotion. The archive is the replay/rollback ground
   truth — with it, any historical identity state can be reconstructed
   exactly.

   Layout:

     .succession/archive/{ts}/promoted/{tier}/{card-id}.md
     .succession/archive/{ts}/promoted.edn

   `ts` is an ISO-like filename-safe timestamp (same shape as
   observation filenames)."
  (:require [clojure.java.io :as io]
            [succession.store.paths :as paths]
            [succession.store.util :as store-util]))

(defn- copy-tree!
  "Recursively copy a directory tree. Creates destination parents as
   needed. Babashka-compatible (no nio Files.walk)."
  [^java.io.File src ^java.io.File dst]
  (when (.exists src)
    (if (.isFile src)
      (do (.mkdirs (.getParentFile dst))
          (io/copy src dst))
      (do (.mkdirs dst)
          (doseq [^java.io.File child (.listFiles src)]
            (copy-tree! child (io/file dst (.getName child))))))))

(defn snapshot!
  "Archive the current promoted tree and snapshot file under
   `archive/{ts}/`. Returns the archive directory path.

   Idempotent per `ts` — calling twice with the same timestamp will
   overwrite. Callers supply `at` (java.util.Date) so the archive is
   deterministic for tests and replay."
  [project-root at]
  (let [ts          (store-util/safe-ts-string at)
        archive-dir (paths/archive-dir project-root ts)
        _           (paths/ensure-dir! archive-dir)
        src-tree    (io/file (paths/promoted-dir project-root))
        dst-tree    (io/file archive-dir "promoted")
        src-edn     (io/file (paths/promoted-snapshot project-root))
        dst-edn     (io/file archive-dir "promoted.edn")]
    (when (.exists src-tree) (copy-tree! src-tree dst-tree))
    (when (.exists src-edn)  (io/copy src-edn dst-edn))
    archive-dir))

(defn list-archives
  "Return archive timestamps (directory names) in lexical order —
   which is also chronological because of the ISO format."
  [project-root]
  (let [root (io/file (paths/archive-dir project-root))]
    (if-not (.exists root)
      []
      (->> (.listFiles root)
           (filter (fn [^java.io.File f] (.isDirectory f)))
           (map (fn [^java.io.File f] (.getName f)))
           sort
           vec))))
