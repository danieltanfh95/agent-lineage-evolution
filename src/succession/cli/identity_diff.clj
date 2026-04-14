(ns succession.cli.identity-diff
  "`succession identity-diff <op>` — compare archived identity snapshots.

   Subcommands:

     list
       Tabulates archive snapshots: timestamp, card counts. Newest first.

     last
       Diff the two most recent archives.

     <ts1> [ts2]
       Diff two specific archives. ts2 defaults to \"current\" (live tree).

   Backward-compat aliases: --list → list, --last → last.
   Bare invocation (no args) diffs the last two archives.

   Changes reported: cards added, removed, retiered, rewritten.

   Reference: `.plans/succession-identity-cycle.md` §CLI surface."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [succession.store.archive :as store-archive]
            [succession.store.cards :as store-cards]
            [succession.store.paths :as paths]))

;; ------------------------------------------------------------------
;; Helpers
;; ------------------------------------------------------------------

(defn- format-ts
  "Convert archive timestamp `2026-04-11T05-36-11-629Z` to readable
   `2026-04-11T05:36:11Z` form."
  [ts]
  (when ts
    (let [date-part (subs ts 0 10)
          rest      (subs ts 11)
          parts     (str/split rest #"-" 4)]
      (if (>= (count parts) 3)
        (str date-part "T" (str/join ":" (take 3 parts)) "Z")
        ts))))

(defn- read-card-count
  "Read :succession/card-count from archive/{ts}/promoted.edn."
  [project-root ts]
  (let [f (io/file (paths/archive-dir project-root ts) "promoted.edn")]
    (when (.exists f)
      (try (:succession/card-count (edn/read-string (slurp f)))
           (catch Throwable _ nil)))))

;; ------------------------------------------------------------------
;; Snapshot loading
;; ------------------------------------------------------------------

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

;; ------------------------------------------------------------------
;; list
;; ------------------------------------------------------------------

(defn- run-list [project-root]
  (let [archives     (store-archive/list-archives project-root)
        newest-first (reverse archives)]
    (if (empty? newest-first)
      (println "succession identity-diff: 0 archive snapshots")
      (do
        (println (format "%-28s  %s" "timestamp" "cards"))
        (println (str (apply str (repeat 28 "-")) "  -----"))
        (doseq [ts newest-first]
          (let [n (read-card-count project-root ts)]
            (println (format "%-28s  %s"
                             (format-ts ts)
                             (if n (str n) "?")))))))
    0))

;; ------------------------------------------------------------------
;; last / diff
;; ------------------------------------------------------------------

(defn- run-last [project-root]
  (let [archives (store-archive/list-archives project-root)
        dirs     (take-last 2 archives)]
    (if (< (count dirs) 2)
      (do (binding [*out* *err*]
            (println "identity-diff last: fewer than 2 archive snapshots found"))
          (System/exit 2))
      (let [ts1    (first dirs)
            ts2    (second dirs)
            before (load-snapshot-cards project-root ts1)
            after  (load-snapshot-cards project-root ts2)
            result (diff-cards before after)]
        (print-report! result {:ts1 ts1 :ts2 ts2})
        (System/exit 0)))))

(defn- run-diff [project-root ts1 ts2]
  (let [ts2 (or ts2 "current")]
    (if (str/blank? ts1)
      (do (binding [*out* *err*]
            (println "usage: succession identity-diff <ts1> [ts2|current]"))
          (System/exit 2))
      (let [before (load-snapshot-cards project-root ts1)
            after  (load-snapshot-cards project-root ts2)
            result (diff-cards before after)]
        (print-report! result {:ts1 ts1 :ts2 ts2})
        (System/exit 0)))))

;; ------------------------------------------------------------------
;; Dispatch
;; ------------------------------------------------------------------

(defn run
  [project-root args]
  (let [[op & rst] args]
    (case op
      ("list" "--list") (run-list project-root)
      ("last" "--last") (run-last project-root)
      nil               (run-last project-root)
      (run-diff project-root op (first rst)))))
