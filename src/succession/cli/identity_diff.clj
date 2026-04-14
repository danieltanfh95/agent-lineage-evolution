(ns succession.cli.identity-diff
  "`succession identity-diff <ts1> <ts2>` — compare
   two archived identity snapshots.

   Every PreCompact run writes `.succession/archive/{ts}/promoted/...`
   before rewriting the live tree. identity-diff loads two of these
   archives (or one archive vs. current) and reports what changed:

     - cards added       (present in ts2, absent in ts1)
     - cards removed     (present in ts1, absent in ts2)
     - cards retiered    (same id, different tier)
     - cards rewritten   (same id, same tier, different text)

   `ts1` and `ts2` are archive directory names as returned by
   `store/archive/list-archives`. `ts2` may also be the literal string
   `current` to diff the most recent archive against the live promoted
   tree.

   Intent: an auditing tool. Not part of the hot path. Run manually
   after a PreCompact to see what a promotion changed.

   Reference: `.plans/succession-identity-cycle.md` §CLI surface."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [succession.store.cards :as store-cards]
            [succession.store.paths :as paths]))

(defn- load-snapshot-cards
  "Load cards from either a named archive or the live promoted tree.
   Returns a map `card-id → card`.

   Archive layout mirrors the live layout, so we temporarily swap in
   an 'archive/{ts}' project-root and let the existing store-cards
   loader do the work. Hacky but avoids duplicating YAML parsing."
  [project-root ts-or-current]
  (let [pseudo-root
        (if (= ts-or-current "current")
          project-root
          (let [archive-ts-dir (paths/archive-dir project-root ts-or-current)]
            ;; store-cards/load-all-cards walks
            ;; `<root>/.succession/identity/promoted/{tier}/`. The
            ;; archive lives at `archive/{ts}/promoted/{tier}/`, so we
            ;; need to build a pseudo-root whose `.succession/identity/
            ;; promoted` lines up. Simplest approach: symlink or prep a
            ;; temp tree. But we can bypass by reading the archive's
            ;; `promoted.edn` snapshot when present — it's already the
            ;; materialized map.
            archive-ts-dir))]
    (if (= ts-or-current "current")
      (into {} (map (juxt :card/id identity)
                    (store-cards/load-all-cards pseudo-root)))
      (let [snap-file (io/file pseudo-root "promoted.edn")]
        (if (.exists snap-file)
          (let [snap (edn/read-string (slurp snap-file))]
            (into {} (map (juxt :card/id identity) (or (:cards snap) []))))
          ;; Fall back to walking the archive tree by hand — reuses the
          ;; same read-card fn store-cards exports.
          (let [tiers (io/file pseudo-root "promoted")]
            (if-not (.exists tiers)
              {}
              (into {}
                    (for [^java.io.File tier-dir (.listFiles tiers)
                          :when (.isDirectory tier-dir)
                          ^java.io.File f (.listFiles tier-dir)
                          :when (str/ends-with? (.getName f) ".md")
                          :let [c (try (store-cards/read-card (.getPath f))
                                       (catch Throwable _ nil))]
                          :when c]
                      [(:card/id c) c])))))))))

(defn diff-cards
  "Pure: compare two id→card maps. Returns
   `{:added :removed :retiered :rewritten}` seqs of card ids
   (rewritten is seq of `{:card-id :from :to}`)."
  [before after]
  (let [before-ids (set (keys before))
        after-ids  (set (keys after))
        added      (sort (clojure.set/difference after-ids before-ids))
        removed    (sort (clojure.set/difference before-ids after-ids))
        common     (clojure.set/intersection before-ids after-ids)
        retiered   (sort (filter (fn [id]
                                   (not= (:card/tier (get before id))
                                         (:card/tier (get after id))))
                                 common))
        rewritten  (->> common
                        (filter (fn [id]
                                  (not= (:card/text (get before id))
                                        (:card/text (get after id)))))
                        sort
                        (map (fn [id]
                               {:card-id id
                                :from    (:card/text (get before id))
                                :to      (:card/text (get after id))})))]
    {:added added :removed removed :retiered retiered :rewritten rewritten}))

(defn print-report!
  [result {:keys [ts1 ts2]}]
  (println (format "identity-diff  %s → %s" ts1 ts2))
  (println)
  (when-let [a (seq (:added result))]
    (println "added:")
    (doseq [id a] (println "  +" id)))
  (when-let [r (seq (:removed result))]
    (println "removed:")
    (doseq [id r] (println "  -" id)))
  (when-let [rt (seq (:retiered result))]
    (println "retiered:")
    (doseq [id rt] (println "  ~" id)))
  (when-let [rw (seq (:rewritten result))]
    (println "rewritten:")
    (doseq [{:keys [card-id from to]} rw]
      (println "  *" card-id)
      (println "    from:" (first (str/split-lines (or from ""))))
      (println "    to:  " (first (str/split-lines (or to ""))))))
  (when (every? empty? [(:added result) (:removed result)
                        (:retiered result) (:rewritten result)])
    (println "(no differences)")))

(defn run
  [project-root args]
  (let [[flag] args]
    (cond
      (= flag "--list")
      (let [archive-base (io/file project-root ".succession" "archive")]
        (if-not (.exists archive-base)
          (println "(no archives found)")
          (doseq [ts (->> (.listFiles archive-base)
                          (filter #(.isDirectory %))
                          (map #(.getName %))
                          sort)]
            (println ts)))
        (System/exit 0))

      (= flag "--last")
      (let [archive-base (io/file project-root ".succession" "archive")
            dirs         (when (.exists archive-base)
                           (->> (.listFiles archive-base)
                                (filter #(.isDirectory %))
                                (map #(.getName %))
                                sort
                                (take-last 2)))]
        (if (< (count dirs) 2)
          (do (binding [*out* *err*]
                (println "identity-diff --last: fewer than 2 archive snapshots found"))
              (System/exit 2))
          (let [ts1    (first dirs)
                ts2    (second dirs)
                before (load-snapshot-cards project-root ts1)
                after  (load-snapshot-cards project-root ts2)
                result (diff-cards before after)]
            (print-report! result {:ts1 ts1 :ts2 ts2})
            (System/exit 0))))

      :else
      (let [[ts1 ts2] args]
        (if (or (str/blank? ts1) (str/blank? ts2))
          (do (binding [*out* *err*]
                (println "usage: succession identity-diff <ts1> <ts2|current>"))
              (System/exit 2))
          (let [before (load-snapshot-cards project-root ts1)
                after  (load-snapshot-cards project-root ts2)
                result (diff-cards before after)]
            (print-report! result {:ts1 ts1 :ts2 ts2})
            (System/exit 0)))))))
