(ns succession.cli.staging
  "`succession staging <op>` — inspect and discard staged sessions.

   Subcommands:

     status                          — session count + oldest/newest dates
     prune                           — delete ALL staging sessions (no promotion)
     prune --keep-last N             — keep N most recent, delete the rest
     prune --older-than <N(s|m|h|d)> — delete sessions older than the threshold

   Unlike `succession compact`, prune does NOT run the promotion
   pipeline — deltas are discarded without being applied to canonical
   identity. Use this when old sessions contain stale or invalid deltas
   you explicitly do not want promoted."
  (:require [succession.store.staging :as store-staging]))

;; ------------------------------------------------------------------
;; Helpers
;; ------------------------------------------------------------------

(defn- err!
  [msg]
  (binding [*out* *err*] (println msg))
  1)

(defn- parse-duration
  "Parse a simple duration string like `7d`, `12h`, `30m`, `90s` into
   milliseconds. Returns nil on parse failure."
  [s]
  (when (and s (re-matches #"^(\d+)([smhd])$" s))
    (let [[_ n unit] (re-matches #"^(\d+)([smhd])$" s)
          n (Long/parseLong n)]
      (* n (case unit
             "s" 1000
             "m" (* 60 1000)
             "h" (* 60 60 1000)
             "d" (* 24 60 60 1000))))))

(defn- format-date
  [mtime]
  (.format (doto (java.text.SimpleDateFormat. "yyyy-MM-dd")
             (.setTimeZone (java.util.TimeZone/getDefault)))
           (java.util.Date. ^long mtime)))

;; ------------------------------------------------------------------
;; status
;; ------------------------------------------------------------------

(defn run-status
  [project-root]
  (let [sessions (store-staging/list-sessions project-root)
        n        (count sessions)]
    (if (zero? n)
      (println "succession staging: 0 sessions")
      (let [newest (-> sessions first :session/mtime)
            oldest (-> sessions last :session/mtime)]
        (println (format "succession staging: %d session(s)  (oldest: %s, newest: %s)"
                         n (format-date oldest) (format-date newest)))))
    0))

;; ------------------------------------------------------------------
;; prune
;; ------------------------------------------------------------------

(defn run-prune
  [project-root args]
  (let [args      (vec args)
        confirm?  (boolean (some #{"--confirm"} args))
        rest-args (vec (remove #{"--confirm"} args))]
    (cond
      (= (first rest-args) "--keep-last")
      (let [n-str (second rest-args)
            n     (when n-str
                    (try (Long/parseLong n-str)
                         (catch NumberFormatException _ nil)))]
        (if (nil? n)
          (err! "usage: succession staging prune --keep-last <N> [--confirm]")
          (let [sessions  (store-staging/list-sessions project-root)
                to-delete (drop n sessions)]
            (if (empty? to-delete)
              (do (println (format "0 staging session(s) to delete (have %d, keeping %d)"
                                   (count sessions) n))
                  0)
              (do
                (println (format "%d staging session(s) to delete%s (keeping %d most recent):"
                                 (count to-delete)
                                 (if confirm? "" " (dry run — add --confirm to delete)")
                                 n))
                (doseq [s to-delete]
                  (println (format "  %s  (%s)" (:session/id s) (format-date (:session/mtime s)))))
                (when confirm?
                  (let [deleted (store-staging/prune-sessions! project-root {:keep-last n})]
                    (println (format "deleted %d staging session(s)" deleted))))
                0)))))

      (= (first rest-args) "--older-than")
      (let [dur-str (second rest-args)
            dur     (parse-duration dur-str)]
        (if (nil? dur)
          (err! "usage: succession staging prune --older-than <N(s|m|h|d)> [--confirm]")
          (let [sessions  (store-staging/list-sessions project-root)
                now-ms    (System/currentTimeMillis)
                to-delete (filter #(> (- now-ms (:session/mtime %)) dur) sessions)]
            (if (empty? to-delete)
              (do (println (format "0 staging session(s) to delete older than %s" dur-str))
                  0)
              (do
                (println (format "%d staging session(s) older than %s%s:"
                                 (count to-delete)
                                 dur-str
                                 (if confirm? "" " (dry run — add --confirm to delete)")))
                (doseq [s to-delete]
                  (println (format "  %s  (%s)" (:session/id s) (format-date (:session/mtime s)))))
                (when confirm?
                  (let [deleted (store-staging/prune-sessions! project-root {:older-than-ms dur})]
                    (println (format "deleted %d staging session(s)" deleted))))
                0)))))

      (empty? rest-args)
      (let [sessions (store-staging/list-sessions project-root)]
        (if (empty? sessions)
          (do (println "0 staging session(s) to delete")
              0)
          (do
            (println (format "%d staging session(s)%s:"
                             (count sessions)
                             (if confirm? "" " (dry run — add --confirm to delete)")))
            (doseq [s sessions]
              (println (format "  %s  (%s)" (:session/id s) (format-date (:session/mtime s)))))
            (when confirm?
              (let [deleted (store-staging/prune-sessions! project-root {})]
                (println (format "deleted %d staging session(s)" deleted))))
            0)))

      :else
      (err! "usage: succession staging prune [--keep-last N | --older-than <N(s|m|h|d)>] [--confirm]"))))

;; ------------------------------------------------------------------
;; Dispatch
;; ------------------------------------------------------------------

(defn run
  [project-root args]
  (let [[op & rest-args] args]
    (case op
      "status" (run-status project-root)
      "prune"  (run-prune project-root rest-args)
      (err! (str "usage: succession staging <status|prune>"
                 (when op (str "\n  unknown subcommand: " op)))))))
