(ns succession.cli.archive
  "`succession archive <op>` — inspect and prune archive snapshots.

   Subcommands:

     list
       Tabulates archive snapshots: timestamp, card count. Sorted newest first.

     prune --keep-last N
       Deletes the oldest (total - N) archive snapshot directories.
       Dry-run by default; add --confirm to execute."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [succession.store.archive :as store-archive]
            [succession.store.paths :as paths]))

;; ------------------------------------------------------------------
;; Helpers
;; ------------------------------------------------------------------

(defn- err! [msg]
  (binding [*out* *err*] (println msg))
  1)

(defn- read-card-count
  "Read :succession/card-count from archive/{ts}/promoted.edn.
   Returns nil if the file is missing or unparseable."
  [project-root ts]
  (let [f (io/file (paths/archive-dir project-root ts) "promoted.edn")]
    (when (.exists f)
      (try (:succession/card-count (edn/read-string (slurp f)))
           (catch Throwable _ nil)))))

(defn- format-ts
  "Convert archive timestamp string `2026-04-11T05-36-11-629Z` to
   a readable `2026-04-11T05:36:11Z` form."
  [ts]
  (when ts
    ;; Replace first 3 `-` separators in time part with `:`
    ;; Pattern: YYYY-MM-DDTHH-MM-SS-mmmZ
    (let [date-part (subs ts 0 10)
          rest      (subs ts 11)]
      ;; rest looks like "05-36-11-629Z"
      (let [parts (str/split rest #"-" 4)]
        (if (>= (count parts) 3)
          (str date-part "T" (str/join ":" (take 3 parts)) "Z")
          ts)))))

(defn- delete-tree! [^java.io.File f]
  (when (.exists f)
    (when (.isDirectory f)
      (doseq [^java.io.File c (.listFiles f)] (delete-tree! c)))
    (.delete f)))

;; ------------------------------------------------------------------
;; list
;; ------------------------------------------------------------------

(defn run-list [project-root]
  (let [archives (store-archive/list-archives project-root)
        newest-first (reverse archives)]
    (if (empty? newest-first)
      (println "succession archive: 0 snapshots")
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
;; prune
;; ------------------------------------------------------------------

(defn run-prune [project-root args]
  (let [args (vec args)]
    (cond
      (= (first args) "--keep-last")
      (let [n-str  (second args)
            n      (when n-str
                     (try (Long/parseLong n-str)
                          (catch NumberFormatException _ nil)))]
        (if (nil? n)
          (err! "usage: succession archive prune --keep-last <N>")
          (let [confirm?  (some #{"--confirm"} args)
                archives  (store-archive/list-archives project-root)
                to-delete (drop-last n archives)]   ; archives sorted oldest-first; drop-last keeps N newest
            (if (empty? to-delete)
              (println (format "0 archive snapshot(s) to delete (have %d, keeping %d)"
                               (count archives) n))
              (do
                (println (format "%d archive snapshot(s) to delete%s:"
                                 (count to-delete)
                                 (if confirm? "" " (dry run — add --confirm to delete)")))
                (doseq [ts to-delete]
                  (println (format "  %s" (format-ts ts))))
                (when confirm?
                  (doseq [ts to-delete]
                    (delete-tree! (io/file (paths/archive-dir project-root ts))))
                  (println (format "deleted %d snapshot(s)" (count to-delete))))))
            0)))

      :else
      (err! "usage: succession archive prune --keep-last <N>"))))

;; ------------------------------------------------------------------
;; Dispatch
;; ------------------------------------------------------------------

(defn run [project-root args]
  (let [[op & rest-args] args]
    (case op
      "list"  (run-list project-root)
      "prune" (run-prune project-root rest-args)
      (err! (str "usage: succession archive <list|prune>"
                 (when op (str "\n  unknown subcommand: " op)))))))
