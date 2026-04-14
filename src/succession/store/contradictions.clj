(ns succession.store.contradictions
  "Persistence for contradiction records produced by
   `domain/reconcile` and `llm/reconcile`.

   Canonical layout:

     .succession/contradictions/{contradiction-id}.edn      ; canonical file
     .succession/staging/{sess}/contradictions.jsonl        ; session log

   The per-session log is for quick \"what did reconcile find in this
   session?\" reads. The canonical per-id files are the source of truth
   for resolution status (so resolving a contradiction is a single file
   update, not an append).

   Pure shape lives in `domain/reconcile`; this namespace is I/O only."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [succession.store.paths :as paths]))

(defn write-contradiction!
  "Write or overwrite the canonical `.edn` for a contradiction.
   Returns the file path."
  [project-root contradiction]
  (let [id   (:contradiction/id contradiction)
        dir  (paths/contradictions-dir project-root)
        _    (paths/ensure-dir! dir)
        file (paths/contradiction-file project-root id)]
    (spit file (pr-str contradiction))
    file))

(defn read-contradiction
  [file-path]
  (edn/read-string (slurp file-path)))

(defn load-all-contradictions
  "Return every contradiction in the canonical dir. Skips parse
   failures with a stderr warning."
  [project-root]
  (let [dir (io/file (paths/contradictions-dir project-root))]
    (if-not (.exists dir)
      []
      (->> (.listFiles dir)
           (filter (fn [^java.io.File f]
                     (and (.isFile f)
                          (str/ends-with? (.getName f) ".edn"))))
           (keep (fn [^java.io.File f]
                   (try (read-contradiction (.getPath f))
                        (catch Throwable t
                          (binding [*out* *err*]
                            (println "failed to parse contradiction"
                                     (.getPath f) "-" (.getMessage t)))
                          nil))))
           vec))))

(defn open-contradictions
  "Return contradictions that are not yet resolved."
  [project-root]
  (->> (load-all-contradictions project-root)
       (remove :contradiction/resolved-at)
       vec))

(defn append-to-session-log!
  "Append a contradiction to the per-session JSONL log for quick
   traversal. Caller is expected to have already written the canonical
   file via `write-contradiction!` — this is the session pointer."
  [project-root session-id contradiction]
  (let [_    (paths/ensure-dir! (paths/staging-dir project-root session-id))
        path (paths/staging-contradictions project-root session-id)]
    (with-open [w (io/writer path :append true)]
      (.write w (pr-str contradiction))
      (.write w "\n"))
    path))

(defn load-session-contradictions
  "Read the per-session contradiction log."
  [project-root session-id]
  (let [path (paths/staging-contradictions project-root session-id)
        f    (io/file path)]
    (if-not (.exists f)
      []
      (with-open [r (io/reader f)]
        (->> (line-seq r)
             (remove str/blank?)
             (map edn/read-string)
             vec)))))

(defn mark-resolved!
  "Update the canonical file: set :contradiction/resolved-at,
   :contradiction/resolved-by, and optionally :contradiction/resolution.
   Reads, mutates, writes. Not concurrency-safe — caller must hold the
   promote lock for multi-contradiction bulk updates, but single-
   contradiction resolves are fine because each contradiction lives in
   its own file."
  ([project-root contradiction-id resolved-by now]
   (mark-resolved! project-root contradiction-id resolved-by now nil))
  ([project-root contradiction-id resolved-by now resolution]
   (let [file (paths/contradiction-file project-root contradiction-id)]
     (when (.exists (io/file file))
       (let [existing (read-contradiction file)
             updated  (cond-> (assoc existing
                                     :contradiction/resolved-at now
                                     :contradiction/resolved-by resolved-by)
                        resolution (assoc :contradiction/resolution resolution))]
         (spit file (pr-str updated))
         updated)))))

(defn mark-rewrite-applied!
  "Set :applied-at on the resolution map, preventing re-application at
   the next PreCompact."
  [project-root contradiction-id now]
  (let [file (paths/contradiction-file project-root contradiction-id)]
    (when (.exists (io/file file))
      (let [existing (read-contradiction file)
            updated  (assoc-in existing [:contradiction/resolution :applied-at] now)]
        (spit file (pr-str updated))
        updated))))
