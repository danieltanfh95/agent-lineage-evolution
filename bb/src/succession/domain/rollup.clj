(ns succession.identity.domain.rollup
  "Collapse a flat observation list into a per-session rollup.

   Motivation: if the agent does 50 Edit operations in a session, we get
   50 `:confirmed` observations against the `prefer-edit-over-write` card.
   Naively counting those 50 would inflate `freq` and let within-session
   repetition dominate the weight signal. Per plan §Observation dedup:

   > `freq` counts distinct sessions, not raw observations. A session with
   > 50 `:confirmed` hits on the same card contributes 1 to `freq`, plus
   > `first-at`/`last-at` that extend `span_days` if the session itself
   > spans real time.

   This namespace does that collapse. Pure. Input is a seq of
   observations, output is a map keyed by session-id. Downstream
   (`domain/weight`) consumes the rollup, not raw observations."
  (:require [succession.identity.domain.observation :as obs]))

(defn- min-inst
  "Earlier of two temporal values. Works for any Comparable pair,
   which covers both `java.util.Date` (what `#inst` reader literal
   produces) and `java.time.Instant` (what `Instant/parse` produces)."
  [a b]
  (if (neg? (compare a b)) a b))

(defn- max-inst [a b] (if (pos? (compare a b)) a b))

(defn- init-session-bucket
  [observation]
  (let [at (:observation/at observation)]
    {:session/at        at
     :session/confirmed 0
     :session/violated  0
     :session/invoked   0
     :session/consulted 0
     :session/contradicted 0
     :session/first-at  at
     :session/last-at   at}))

(defn- fold-observation
  [bucket observation]
  (let [at    (:observation/at observation)
        kind  (:observation/kind observation)
        count-key (case kind
                    :confirmed    :session/confirmed
                    :violated     :session/violated
                    :invoked      :session/invoked
                    :consulted    :session/consulted
                    :contradicted :session/contradicted)]
    (-> bucket
        (update count-key inc)
        (update :session/first-at min-inst at)
        (update :session/last-at  max-inst at)
        (assoc  :session/at (min-inst (:session/at bucket) at)))))

(defn rollup-by-session
  "Group observations by `:observation/session`, collapse each group into
   a single bucket summarising counts + session first/last timestamps.

   Returns a map from session-id → bucket. Deterministic given the input;
   no I/O, no timestamps read from the clock."
  [observations]
  (reduce
    (fn [acc observation]
      (let [sess (:observation/session observation)]
        (if (contains? acc sess)
          (update acc sess fold-observation observation)
          (assoc acc sess (fold-observation (init-session-bucket observation) observation)))))
    {}
    observations))

(defn sessions-ordered
  "Given a rollup map, return a seq of buckets ordered by
   `:session/first-at` ascending. Used by span and gap-crossing
   computations that need deterministic ordering."
  [rollup]
  (sort-by :session/first-at (vals rollup)))

(defn total
  "Sum a count field across all sessions in a rollup. Returns 0 if the
   rollup is empty."
  [rollup count-key]
  (reduce + 0 (map count-key (vals rollup))))

(defn total-confirmed    [rollup] (total rollup :session/confirmed))
(defn total-violated     [rollup] (total rollup :session/violated))
(defn total-invoked      [rollup] (total rollup :session/invoked))
(defn total-consulted    [rollup] (total rollup :session/consulted))
(defn total-contradicted [rollup] (total rollup :session/contradicted))

(defn distinct-sessions
  "How many distinct sessions has this card been observed in? This is
   the `freq` input to the weight formula — not the raw observation
   count."
  [rollup]
  (count rollup))

(defn violation-rate
  "Rate of violations vs confirmations. Returns 0.0 if there are no
   weight-contributing observations. `:consulted` is weight-neutral and
   excluded from both numerator and denominator."
  [rollup]
  (let [v (total-violated rollup)
        c (total-confirmed rollup)
        i (total-invoked rollup)
        denom (+ v c i)]
    (if (zero? denom) 0.0 (/ (double v) denom))))
