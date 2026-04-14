(ns succession.cli.statusline
  "`succession statusline` — Claude Code statusline provider.

   Reads `{\"session_id\":\"...\"}` from stdin, counts observations and
   queue state, prints a single formatted line to stdout:

     succession: ✓5 ✗1 · judging 3

   Exits 0 always. Prints empty line on any error so the statusline
   never causes Claude Code to surface an exception."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [succession.store.observations :as store-obs]
            [succession.store.jobs :as store-jobs]))

(defn- count-observations
  "Given a session's observations, return `{:confirmed N :violated N}`.
   `:confirmed` includes both `:confirmed` and `:invoked` kinds."
  [observations]
  (let [freqs (frequencies (map :observation/kind observations))]
    {:confirmed (+ (get freqs :confirmed 0)
                   (get freqs :invoked 0))
     :violated  (get freqs :violated 0)}))

(defn- format-line
  "Assemble the statusline string from computed counts.

   Format rules:
   - ✓N shown when confirmed > 0
   - ✗N shown when violated > 0  (space-joined with ✓N, no dot)
   - `judging N` when pending+inflight > 0
   - `N failed` when dead > 0
   - `idle` only when queue empty AND no failed jobs
   - Sections separated by ` · `"
  [{:keys [confirmed violated pending inflight dead]}]
  (let [judging     (+ pending inflight)
        obs-section (str/join " " (cond-> []
                                    (pos? confirmed) (conj (str "✓" confirmed))
                                    (pos? violated)  (conj (str "✗" violated))))
        queue-parts (cond-> []
                      (pos? judging) (conj (str "judging " judging))
                      (pos? dead)    (conj (str dead " failed")))
        queue-parts (if (empty? queue-parts) ["idle"] queue-parts)
        sections    (cond-> []
                      (seq obs-section) (conj obs-section)
                      true              (into queue-parts))]
    (str "succession: " (str/join " · " sections))))

(defn run
  "Entry point. Reads stdin JSON, computes counts, prints one line."
  [project-root]
  (try
    (let [input      (json/parse-string (slurp *in*) true)
          session-id (:session_id input)
          obs        (if session-id
                       (store-obs/load-session-observations
                         project-root session-id)
                       [])
          obs-counts (count-observations obs)
          counts     (merge obs-counts
                            {:pending  (store-jobs/count-pending project-root)
                             :inflight (store-jobs/count-inflight project-root)
                             :dead     (store-jobs/count-dead project-root)})]
      (println (format-line counts))
      0)
    (catch Throwable _
      (println "")
      0)))
