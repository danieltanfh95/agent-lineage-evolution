(ns succession.cli.contradictions
  "`succession contradictions <op>` — inspect and clear contradiction files.

   Subcommands:

     list
       Tabulates all contradiction files: id, category, status (open/resolved),
       resolved-at date. Sorted: open first, then resolved by date desc.

     clear-resolved [--older-than N(s|m|h|d)]
       Deletes contradiction .edn files where :contradiction/resolved-at is non-nil.
       With --older-than, only those resolved more than the given duration ago.
       Dry-run by default; add --confirm to execute."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [succession.store.contradictions :as store-contras]
            [succession.store.paths :as paths]))

;; ------------------------------------------------------------------
;; Helpers
;; ------------------------------------------------------------------

(defn- err! [msg]
  (binding [*out* *err*] (println msg))
  1)

(defn- parse-duration
  "Parse `7d`, `12h`, `30m`, `90s` → milliseconds. Returns nil on failure."
  [s]
  (when (and s (re-matches #"^(\d+)([smhd])$" s))
    (let [[_ n unit] (re-matches #"^(\d+)([smhd])$" s)
          n (Long/parseLong n)]
      (* n (case unit
             "s" 1000
             "m" (* 60 1000)
             "h" (* 3600 1000)
             "d" (* 86400 1000))))))

(defn- format-date [inst]
  (when inst
    (let [d (if (instance? java.util.Date inst) inst
                (java.util.Date/from ^java.time.Instant inst))]
      (.format (doto (java.text.SimpleDateFormat. "yyyy-MM-dd")
                 (.setTimeZone (java.util.TimeZone/getTimeZone "UTC")))
               d))))

;; ------------------------------------------------------------------
;; list
;; ------------------------------------------------------------------

(defn run-list [project-root]
  (let [all  (store-contras/load-all-contradictions project-root)
        open (filter #(nil? (:contradiction/resolved-at %)) all)
        resolved (sort-by #(some-> (:contradiction/resolved-at %) .getTime -)
                          (remove #(nil? (:contradiction/resolved-at %)) all))
        sorted (concat open resolved)]
    (if (empty? sorted)
      (println "succession contradictions: 0 contradictions")
      (do
        (println (format "%-40s  %-22s  %-10s  %s"
                         "id" "category" "status" "resolved-at"))
        (println (str (apply str (repeat 40 "-")) "  "
                      (apply str (repeat 22 "-")) "  "
                      (apply str (repeat 10 "-")) "  "
                      (apply str (repeat 10 "-"))))
        (doseq [c sorted]
          (let [id       (or (:contradiction/id c) "?")
                cat      (some-> (:contradiction/category c) name)
                resolved (:contradiction/resolved-at c)
                status   (if resolved "resolved" "open")]
            (println (format "%-40s  %-22s  %-10s  %s"
                             (if (> (count id) 40) (str (subs id 0 37) "...") id)
                             (or cat "")
                             status
                             (if resolved (format-date resolved) "")))))))
    0))

;; ------------------------------------------------------------------
;; clear-resolved
;; ------------------------------------------------------------------

(defn run-clear-resolved [project-root args]
  (let [args     (vec args)
        confirm? (some #{"--confirm"} args)
        older-ms (when (= "--older-than" (first args))
                   (parse-duration (second args)))]
    (when (and (= "--older-than" (first args)) (nil? older-ms))
      (err! "usage: succession contradictions clear-resolved [--older-than N(s|m|h|d)] [--confirm]"))
    (let [all      (store-contras/load-all-contradictions project-root)
          resolved (remove #(nil? (:contradiction/resolved-at %)) all)
          now-ms   (System/currentTimeMillis)
          to-clear (if older-ms
                     (filter (fn [c]
                               (let [r (:contradiction/resolved-at c)
                                     age (- now-ms (.getTime ^java.util.Date r))]
                                 (> age older-ms)))
                             resolved)
                     resolved)]
      (if (empty? to-clear)
        (println (str "0 resolved contradiction(s) to clear"
                      (when older-ms (str " older than " (second args)))))
        (do
          (println (format "%d resolved contradiction(s)%s%s:"
                           (count to-clear)
                           (if older-ms (str " older than " (second args)) "")
                           (if confirm? "" " (dry run — add --confirm to delete)")))
          (doseq [c to-clear]
            (println (format "  %s  (resolved %s)"
                             (:contradiction/id c)
                             (format-date (:contradiction/resolved-at c)))))
          (when confirm?
            (doseq [c to-clear]
              (let [f (io/file (paths/contradiction-file
                                 project-root (:contradiction/id c)))]
                (when (.exists f) (.delete f))))
            (println (format "deleted %d contradiction file(s)" (count to-clear))))))
      0)))

;; ------------------------------------------------------------------
;; Dispatch
;; ------------------------------------------------------------------

(defn run [project-root args]
  (let [[op & rest-args] args]
    (case op
      "list"           (run-list project-root)
      "clear-resolved" (run-clear-resolved project-root rest-args)
      (err! (str "usage: succession contradictions <list|clear-resolved>"
                 (when op (str "\n  unknown subcommand: " op)))))))
