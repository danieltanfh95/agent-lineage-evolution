(ns succession.identity.hook.common
  "Shared plumbing used by every hook entry point.

   The hook entry contract, per plan §The cycle, hook by hook:

     - read one JSON object from *in* (the Claude Code hook payload)
     - derive `project-root` from the `:cwd` field (never fall back to
       `user.dir` inside a hook — the harness tells us where the user is)
     - load config from `<project-root>/.succession/config.edn`
     - do hook-specific work
     - emit zero or one JSON object on *out* (the hookSpecificOutput)

   This namespace centralizes the read/emit/score bits so individual
   hook files can focus on their domain logic. Nothing here is load-
   bearing for tests — the hook tests exercise the hook's `run` fn
   directly with synthetic stdin.

   Reference: `.plans/succession-identity-cycle.md` §Layer split
   (hook/ imports from domain+store+llm), §Data flow."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [succession.identity.config :as config]
            [succession.identity.domain.rollup :as rollup]
            [succession.identity.domain.weight :as weight]
            [succession.identity.store.observations :as store-obs]))

;; ------------------------------------------------------------------
;; stdin / stdout
;; ------------------------------------------------------------------

(defn read-input
  "Parse one JSON object from *in*. Returns an empty map on parse
   failure — hooks must be fail-safe and never throw uncaught."
  []
  (try
    (let [raw (slurp *in*)]
      (if (str/blank? raw) {} (json/parse-string raw true)))
    (catch Throwable _ {})))

(defn emit-additional-context!
  "Print a `{:hookSpecificOutput {:hookEventName ... :additionalContext text}}`
   JSON blob to stdout. No-op when `text` is blank — Claude Code is happy
   with an empty stdout and we avoid noise in the transcript."
  [hook-event-name text]
  (when (and text (not (str/blank? text)))
    (println
      (json/generate-string
        {:hookSpecificOutput
         {:hookEventName     hook-event-name
          :additionalContext text}}))))

(defn project-root
  "Derive the project root for this hook invocation. Prefers the `:cwd`
   field from the hook payload so one harness instance can drive many
   projects without colliding. Falls back to `user.dir` for local
   testing."
  [input]
  (or (:cwd input) (System/getProperty "user.dir") "."))

(defn load-config
  "Load the effective config for the hook. Cached per-invocation by the
   caller if needed; this fn does a single disk read."
  [input]
  (config/load-config (project-root input)))

;; ------------------------------------------------------------------
;; Card metrics — used by post_tool_use salience, pre_compact retier,
;; stop reconcile. Duplicating this logic in each hook would be dumb,
;; so we centralize here.
;; ------------------------------------------------------------------

(defn metrics-for
  "Compute `{:weight :violation-rate :gap-crossings}` for a card given
   its observation seq + the current time + effective config.

   All three are inputs to `domain/tier/propose-transition`."
  [observations now config]
  (let [r       (rollup/rollup-by-session observations)
        w       (weight/compute r now config)
        vr      (rollup/violation-rate r)
        ordered (rollup/sessions-ordered r)
        gaps    (if (<= (count ordered) 1)
                  0
                  (reduce (fn [acc [prev curr]]
                            (if (pos? (compare (:session/first-at curr)
                                               (:session/last-at prev)))
                              (inc acc)
                              acc))
                          0
                          (partition 2 1 ordered)))]
    {:weight         w
     :violation-rate vr
     :gap-crossings  gaps}))

(defn- recency-fraction
  "0..1 fraction of freshness. 1 = just observed, 0 = older than the
   decay half-life."
  [rollup-map now half-life-days]
  (if (empty? rollup-map)
    0.0
    (let [last-at (reduce #(if (pos? (compare %1 %2)) %1 %2)
                          (map :session/last-at (vals rollup-map)))
          age-ms  (- (.getTime ^java.util.Date now)
                     (.getTime ^java.util.Date last-at))
          age-days (max 0.0 (/ age-ms 86400000.0))]
      (max 0.0 (min 1.0 (- 1.0 (/ age-days (double half-life-days))))))))

(defn score-cards
  "Build the `[{:card :weight :recency-fraction}]` shape that salience
   and consult expect. Reads all observations off disk once and groups
   by card id for O(cards) folding.

   Cards with no observations still appear — they get weight 0 and
   recency 0, which keeps tier-baseline scoring non-empty so a
   freshly-created card isn't invisible until its first observation
   lands."
  [project-root cards config now]
  (let [by-card   (store-obs/observations-by-card
                    (store-obs/load-all-observations project-root))
        half-life (or (:weight/decay-half-life-days config) 180)]
    (mapv (fn [c]
            (let [obs (get by-card (:card/id c) [])
                  r   (rollup/rollup-by-session obs)]
              {:card              c
               :weight            (weight/compute r now config)
               :recency-fraction  (recency-fraction r now half-life)}))
          cards)))
