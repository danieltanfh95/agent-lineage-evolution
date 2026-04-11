(ns succession.identity.store.staging
  "Intra-session delta log + materialized snapshot.

   All intra-session identity changes (new card proposals, text rewrites,
   tier proposals, merges, contradiction markers) are appended to
   `.succession/staging/{session-id}/deltas.jsonl` as an append-only log.
   At Stop (and always at PreCompact), the log is folded through a pure
   function to produce `snapshot.edn`, which is the consult/PreTool/
   PostTool hot-path read.

   The delta log is the source of truth; snapshot is a materialized view
   that can be rebuilt from the log at any time.

   JSONL for the log (append-safe, single-line, line-oriented scanning);
   EDN for the snapshot (whole-value replace, reader literals).

   Reference: `.plans/succession-identity-cycle.md` §Delta (staging log)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [succession.identity.store.paths :as paths]))

;; ------------------------------------------------------------------
;; JSONL writer/reader for deltas
;;
;; We don't use a JSON library here because deltas can carry `#inst`
;; values. Instead, each line is an EDN form produced by pr-str. That
;; makes the file technically EDNL not JSONL, but the plan's "JSONL"
;; choice is about line-orientation (append-safe, tail -f, wc -l) not
;; about the specific serialization format. EDN-per-line gives us the
;; same properties plus reader literals.
;; ------------------------------------------------------------------

(def valid-delta-kinds
  "Closed set per plan §Delta."
  #{:observe-card :create-card :update-card-text :propose-tier
    :propose-merge :mark-contradiction})

(def valid-delta-sources
  #{:judge :extract :user-correction :reconcile :consult})

(defn make-delta
  "Construct a delta record. All fields required except :card-id (may
   be nil for :create-card) and :payload."
  [{:keys [id at kind card-id payload source]}]
  {:pre [(string? id)
         (inst? at)
         (contains? valid-delta-kinds kind)
         (contains? valid-delta-sources source)]}
  (cond-> {:succession/entity-type :delta
           :delta/id     id
           :delta/at     at
           :delta/kind   kind
           :delta/source source}
    card-id (assoc :delta/card-id card-id)
    payload (assoc :delta/payload payload)))

(defn append-delta!
  "Append a delta line to the session's staging log. Creates the
   session staging dir on first write. Returns the delta that was
   written."
  [project-root session-id delta]
  (let [_    (paths/ensure-dir! (paths/staging-dir project-root session-id))
        path (paths/staging-deltas project-root session-id)]
    (with-open [w (io/writer path :append true)]
      (.write w (pr-str delta))
      (.write w "\n"))
    delta))

(defn load-deltas
  "Return the seq of deltas for a session, in file order (which is
   write order since the log is append-only). Returns `[]` if the log
   does not exist."
  [project-root session-id]
  (let [path (paths/staging-deltas project-root session-id)
        f    (io/file path)]
    (if-not (.exists f)
      []
      (with-open [r (io/reader f)]
        (->> (line-seq r)
             (remove str/blank?)
             (map read-string)
             vec)))))

;; ------------------------------------------------------------------
;; Snapshot materialization (pure fold over deltas)
;; ------------------------------------------------------------------

(defn- apply-delta
  "Pure fold step. `snap` is a map of snapshot state; `delta` updates
   one field. The snapshot is a minimal projection — full reconciliation
   happens at PreCompact, not here."
  [snap delta]
  (let [k  (:delta/kind delta)
        id (:delta/card-id delta)]
    (case k
      :observe-card
      (update-in snap [:staging/observation-counts id] (fnil inc 0))

      :create-card
      (update snap :staging/created-cards
              (fnil conj []) (:delta/payload delta))

      :update-card-text
      (assoc-in snap [:staging/text-rewrites id] (:delta/payload delta))

      :propose-tier
      (assoc-in snap [:staging/tier-proposals id] (:delta/payload delta))

      :propose-merge
      (update snap :staging/merge-proposals
              (fnil conj []) (:delta/payload delta))

      :mark-contradiction
      (update snap :staging/contradiction-ids
              (fnil conj []) (or id (get-in delta [:delta/payload :contradiction/id]))))))

(defn materialize-snapshot
  "Pure: fold a delta seq into a staging snapshot map. Deterministic
   given the same delta ordering."
  [deltas]
  (reduce apply-delta
          {:succession/snapshot-version 1
           :staging/observation-counts  {}
           :staging/text-rewrites       {}
           :staging/tier-proposals      {}
           :staging/created-cards       []
           :staging/merge-proposals     []
           :staging/contradiction-ids   []}
          deltas))

(defn write-snapshot!
  "Write the materialized snapshot for a session."
  [project-root session-id snapshot]
  (let [_    (paths/ensure-dir! (paths/staging-dir project-root session-id))
        path (paths/staging-snapshot project-root session-id)]
    (spit path (pr-str snapshot))
    path))

(defn read-snapshot
  "Read the materialized snapshot. Returns nil if none written yet."
  [project-root session-id]
  (let [path (paths/staging-snapshot project-root session-id)
        f    (io/file path)]
    (when (.exists f)
      (read-string (slurp f)))))

(defn rematerialize!
  "Re-fold the delta log and overwrite the snapshot. Called at Stop
   (to refresh the consult hot-path view) and at PreCompact."
  [project-root session-id]
  (let [deltas (load-deltas project-root session-id)
        snap   (materialize-snapshot deltas)]
    (write-snapshot! project-root session-id snap)
    snap))

(defn- delete-tree! [^java.io.File f]
  (when (.exists f)
    (when (.isDirectory f)
      (doseq [^java.io.File c (.listFiles f)] (delete-tree! c)))
    (.delete f)))

(defn clear-session!
  "Delete the entire staging directory for a session. Called by
   PreCompact after the deltas have been promoted and archived — staging
   is then empty ground for the next session.

   Returns true if the directory existed and was deleted, false otherwise."
  [project-root session-id]
  (let [dir (io/file (paths/staging-dir project-root session-id))]
    (if (.exists dir)
      (do (delete-tree! dir) true)
      false)))
