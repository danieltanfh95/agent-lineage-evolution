(ns succession.cli.observations
  "`succession observations <op>` — inspect and prune observation files.

   Subcommands:

     status
       Shows total count, per-card-id counts (sorted by count desc),
       and oldest/newest session dates.

     prune --older-than <N(s|m|h|d)>
       Deletes session subdirectories under observations/ whose directory
       mtime (proxy for last-observation time) is older than the threshold.
       Dry-run by default; add --confirm to execute."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [succession.cli.common :as cli-common]
            [succession.store.observations :as store-obs]
            [succession.store.paths :as paths]))

;; ------------------------------------------------------------------
;; Helpers
;; ------------------------------------------------------------------

(defn- err! [msg]
  (binding [*out* *err*] (println msg))
  1)

(defn- format-date [^long mtime]
  (.format (java.text.SimpleDateFormat. "yyyy-MM-dd")
           (java.util.Date. mtime)))

(defn- obs-session-dirs
  "Return File objects for each session directory under observations/."
  [project-root]
  (let [root (io/file (paths/observations-dir project-root))]
    (if-not (.exists root)
      []
      (->> (.listFiles root)
           (filter #(.isDirectory ^java.io.File %))
           vec))))

(defn- delete-tree! [^java.io.File f]
  (when (.exists f)
    (when (.isDirectory f)
      (doseq [^java.io.File c (.listFiles f)] (delete-tree! c)))
    (.delete f)))

;; ------------------------------------------------------------------
;; status
;; ------------------------------------------------------------------

(defn run-status [project-root]
  (let [all-obs (store-obs/load-all-observations project-root)
        n       (count all-obs)]
    (if (zero? n)
      (println "succession observations: 0 observations")
      (let [by-card (->> all-obs
                         (group-by :observation/card-id)
                         (map (fn [[id v]] {:card-id id :count (count v)}))
                         (sort-by :count #(compare %2 %1)))
            dirs    (obs-session-dirs project-root)
            mtimes  (map #(.lastModified ^java.io.File %) dirs)
            oldest  (when (seq mtimes) (apply min mtimes))
            newest  (when (seq mtimes) (apply max mtimes))]
        (println (format "succession observations: %d total  %d session%s"
                         n (count dirs) (if (= 1 (count dirs)) "" "s")))
        (when oldest
          (println (format "  oldest session: %s  newest: %s"
                           (format-date oldest) (format-date newest))))
        (println)
        (println "  by card (observations desc):")
        (doseq [{:keys [card-id count]} (take 20 by-card)]
          (println (format "    %-40s  %d" card-id count)))
        (when (> (count by-card) 20)
          (println (format "    ... and %d more card(s)" (- (count by-card) 20))))))
    0))

;; ------------------------------------------------------------------
;; prune
;; ------------------------------------------------------------------

(defn run-prune [project-root args]
  (let [args (vec args)]
    (cond
      (= (first args) "--older-than")
      (let [dur-str (second args)
            dur     (cli-common/parse-duration dur-str)]
        (if (nil? dur)
          (err! "usage: succession observations prune --older-than <N(s|m|h|d)>")
          (let [confirm? (some #{"--confirm"} args)
                now-ms   (System/currentTimeMillis)
                dirs     (obs-session-dirs project-root)
                old      (filter #(> (- now-ms (.lastModified ^java.io.File %)) dur) dirs)]
            (if (empty? old)
              (println (format "0 observation session(s) older than %s" dur-str))
              (do
                (println (format "%d observation session(s) older than %s%s:"
                                 (count old) dur-str
                                 (if confirm? "" " (dry run — add --confirm to delete)")))
                (doseq [^java.io.File d old]
                  (println (format "  %s  (last modified %s)"
                                   (.getName d) (format-date (.lastModified d)))))
                (when confirm?
                  (doseq [^java.io.File d old] (delete-tree! d))
                  (println (format "deleted %d session(s)" (count old))))))
            0)))

      :else
      (err! "usage: succession observations prune --older-than <N(s|m|h|d)>"))))

;; ------------------------------------------------------------------
;; Dispatch
;; ------------------------------------------------------------------

(defn run [project-root args]
  (let [[op & rest-args] args]
    (case op
      "status" (run-status project-root)
      "prune"  (run-prune project-root rest-args)
      (err! (str "usage: succession observations <status|prune>"
                 (when op (str "\n  unknown subcommand: " op)))))))
