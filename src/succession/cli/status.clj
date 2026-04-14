(ns succession.cli.status
  "`succession status` — top-level dashboard of the whole .succession/ folder.

   Output:
     identity   8 cards  (3 principle · 3 rule · 2 ethic)
                last compact: 2026-04-14T09:36Z

     store      350 observations  29 sessions
                11 archive snapshots  (oldest: 2026-04-11)
                18 contradictions  (4 open · 14 resolved)

     queue      0 pending · 0 inflight · 0 dead
     staging    1 session pending compact

   No side effects."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [succession.store.archive :as store-archive]
            [succession.store.cards :as store-cards]
            [succession.store.contradictions :as store-contras]
            [succession.store.jobs :as store-jobs]
            [succession.store.observations :as store-obs]
            [succession.store.paths :as paths]
            [succession.store.staging :as store-staging]))

(defn- format-instant
  "Format a java.util.Date or java.time.Instant as `yyyy-MM-ddTHH:mmZ`."
  [inst]
  (when inst
    (let [i (cond
              (instance? java.util.Date inst) (.toInstant ^java.util.Date inst)
              :else inst)]
      (-> (.truncatedTo ^java.time.Instant i java.time.temporal.ChronoUnit/MINUTES)
          .toString
          (str/replace ":00Z" "Z")))))

(defn- format-date
  "Extract just the date portion from an archive timestamp string like
   `2026-04-11T05-36-11-629Z`."
  [ts]
  (when (and ts (>= (count ts) 10))
    (subs ts 0 10)))

(defn- count-obs-sessions
  "Count distinct session directories under observations/."
  [project-root]
  (let [root (io/file (paths/observations-dir project-root))]
    (if-not (.exists root)
      0
      (->> (.listFiles root)
           (filter #(.isDirectory ^java.io.File %))
           count))))

(defn run
  [project-root _args]
  (let [snap        (store-cards/read-promoted-snapshot project-root)
        cards       (or (:cards snap) [])
        tier-counts (frequencies (map :card/tier cards))
        last-compact (:at snap)

        obs-total   (count (store-obs/load-all-observations project-root))
        obs-sessions (count-obs-sessions project-root)

        archives    (store-archive/list-archives project-root)
        arch-count  (count archives)
        arch-oldest (format-date (first archives))

        contras     (store-contras/load-all-contradictions project-root)
        open-count  (count (remove :contradiction/resolved-at contras))
        resolved-count (- (count contras) open-count)

        pending     (store-jobs/count-pending project-root)
        inflight    (store-jobs/count-inflight project-root)
        dead        (store-jobs/count-dead project-root)

        staging-sessions (count (store-staging/list-sessions project-root))]

    (println (format "identity   %d card%s  (%s)"
                     (count cards)
                     (if (= 1 (count cards)) "" "s")
                     (str/join " · "
                               (keep (fn [tier]
                                       (when-let [n (get tier-counts tier)]
                                         (str n " " (name tier))))
                                     [:principle :rule :ethic]))))
    (println (format "           last compact: %s"
                     (if last-compact (format-instant last-compact) "never")))
    (println)
    (println (format "store      %d observation%s  %d session%s"
                     obs-total (if (= 1 obs-total) "" "s")
                     obs-sessions (if (= 1 obs-sessions) "" "s")))
    (println (format "           %d archive snapshot%s%s"
                     arch-count
                     (if (= 1 arch-count) "" "s")
                     (if arch-oldest (str "  (oldest: " arch-oldest ")") "")))
    (println (format "           %d contradiction%s  (%d open · %d resolved)"
                     (count contras)
                     (if (= 1 (count contras)) "" "s")
                     open-count resolved-count))
    (println)
    (println (format "queue      %d pending · %d inflight · %d dead"
                     pending inflight dead))
    (println (format "staging    %d session%s pending compact"
                     staging-sessions
                     (if (= 1 staging-sessions) "" "s")))
    0))
