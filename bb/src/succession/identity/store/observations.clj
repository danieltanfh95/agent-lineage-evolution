(ns succession.identity.store.observations
  "Per-observation EDN files under
   `.succession/observations/{session-id}/{ts}-{uuid}.edn`.

   Per plan §Observation (the real atom): one file per observation
   avoids concurrent-append corruption. Many small files rather than
   a single JSONL. The session directory is scanned at card-load time
   and the observations are folded through `domain/rollup` to produce
   the per-session rollup.

   EDN (not JSONL) because observations carry `#inst` values and we
   round-trip via `read-string` + `pr-str`, which handles reader literals
   natively.

   This namespace is the I/O half; the pure shape lives in
   `domain/observation`."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [succession.identity.store.paths :as paths]))

(defn- safe-ts-string
  "Convert an inst into a filename-safe ISO-like string: no colons or
   dots. e.g. `2026-04-11T12-34-56-789Z`."
  [inst]
  (-> (cond
        (instance? java.util.Date inst)  (.toInstant ^java.util.Date inst)
        (instance? java.time.Instant inst) inst
        :else (java.time.Instant/now))
      .toString
      (str/replace ":" "-")
      (str/replace "." "-")))

(defn write-observation!
  "Write one observation to its canonical file under the session dir.
   Returns the written file path. The caller supplies the observation
   map directly (already constructed via `domain/observation/make-observation`)."
  [project-root observation]
  (let [session (:observation/session observation)
        ts      (safe-ts-string (:observation/at observation))
        uuid    (:observation/id observation)
        dir     (paths/observations-dir project-root session)
        _       (paths/ensure-dir! dir)
        file    (paths/observation-file project-root session ts uuid)]
    (spit file (pr-str observation))
    file))

(defn read-observation
  "Read a single observation file back into a map. `pr-str`/`read-string`
   round-trips `#inst`, keywords, and sets natively."
  [file-path]
  (read-string (slurp file-path)))

(defn- list-edn-files
  [^java.io.File dir]
  (when (.exists dir)
    (->> (.listFiles dir)
         (filter (fn [^java.io.File f]
                   (and (.isFile f)
                        (str/ends-with? (.getName f) ".edn")))))))

(defn load-session-observations
  "Return every observation stored for a single session, in no
   particular order. Returns `[]` if the session directory does not
   exist."
  [project-root session-id]
  (let [dir (io/file (paths/observations-dir project-root session-id))]
    (->> (list-edn-files dir)
         (keep (fn [^java.io.File f]
                 (try (read-observation (.getPath f))
                      (catch Throwable _ nil))))
         vec)))

(defn load-all-observations
  "Scan every session directory and return all observations as one flat
   seq. Intended for small/medium stores; larger stores should pass a
   session-id filter via `load-session-observations`."
  [project-root]
  (let [root (io/file (paths/observations-dir project-root))]
    (if-not (.exists root)
      []
      (->> (.listFiles root)
           (filter (fn [^java.io.File f] (.isDirectory f)))
           (mapcat (fn [^java.io.File d]
                     (load-session-observations project-root (.getName d))))
           vec))))

(defn observations-by-card
  "Group a flat observation seq by `:observation/card-id`. Returned as
   a plain map from card-id → vector of observations, suitable for
   passing to `domain/reconcile/detect-all`."
  [observations]
  (->> observations
       (group-by :observation/card-id)
       (into {} (map (fn [[k v]] [k (vec v)])))))
