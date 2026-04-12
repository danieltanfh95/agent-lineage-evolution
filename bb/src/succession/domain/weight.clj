(ns succession.domain.weight
  "Pure weight formula for identity cards.

   The 資治通鑑 principle: a claim observed long ago and re-confirmed
   now is more load-bearing than a claim seen only recently, even at
   equal or higher raw frequency. Span dominates frequency. Gap-crossings
   multiply. Stale claims decay.

   Reference: `.plans/succession-identity-cycle.md` §Weight formula.

   The formula, with all knobs coming from config (never hardcoded):

     freq           = distinct-sessions count (from rollup)
     span_days      = days between first and last observation
     gap_crossings  = sessions whose start follows the previous session's
                      end by at least `:weight/gap-threshold-sessions`
                      (i.e. there was a real dormant gap)
     violation_rate = rate (from rollup, :consulted excluded)

     decay          = 0.5 ^ (days_since_last_reinforce / half_life)
     base           = min(sqrt(freq), freq_cap)
                    * (1 + log(1 + span_days))^span_exponent
                    * (1 + gap_crossings)
                    * (if (zero? gap_crossings) within_session_penalty 1)
                    * decay
     penalty        = base * violation_rate * violation_penalty_rate
     weight         = max 0 (base - penalty)

   This namespace takes no I/O. The caller supplies observations (or a
   rollup derived from them) plus `now` (so the decay is deterministic
   for tests and replay) plus the config. Cards are NOT passed in — the
   same card's text is irrelevant to weight. Only the rollup and `now`
   matter."
  (:require [succession.domain.rollup :as rollup]))

(defn- ms->days [ms] (double (/ ms 86400000.0)))

(defn- temporal-span-days
  "Days between the earliest first-at and latest last-at across all
   sessions in the rollup. Zero for a single observation."
  [rollup-map]
  (let [buckets (vals rollup-map)]
    (if (empty? buckets)
      0.0
      (let [firsts (map :session/first-at buckets)
            lasts  (map :session/last-at  buckets)
            min-first (.getTime ^java.util.Date (reduce #(if (neg? (compare %1 %2)) %1 %2) firsts))
            max-last  (.getTime ^java.util.Date (reduce #(if (pos? (compare %1 %2)) %1 %2) lasts))]
        (ms->days (- max-last min-first))))))

(defn- gap-crossings
  "How many session boundaries are real dormant gaps? Walks sessions in
   order of first-at; each session (after the first) whose first-at
   comes strictly after the previous session's last-at counts as a
   gap-crossing. This is the plan's definition of `gap_crossings`:
   sessions separated by at least one dormant period.

   Note: current implementation treats *any* positive gap between one
   session's last-at and the next session's first-at as a crossing,
   because our session model says a session has a clean start/end and
   two distinct sessions are by definition separated in time. The
   `gap-threshold-sessions` config knob is reserved for a later
   refinement that requires N dormant sessions between — we start with
   the simpler rule and tune on real data."
  [rollup-map _config]
  (let [ordered (rollup/sessions-ordered rollup-map)]
    (if (<= (count ordered) 1)
      0
      (reduce
        (fn [acc [prev curr]]
          (if (pos? (compare (:session/first-at curr) (:session/last-at prev)))
            (inc acc)
            acc))
        0
        (partition 2 1 ordered)))))

(defn- last-reinforcement
  "Timestamp of the most recent weight-contributing observation. Nil if
   the rollup has nothing contributing."
  [rollup-map]
  (let [buckets (vals rollup-map)
        ;; Only sessions where something other than pure consulted activity
        ;; happened count toward reinforcement. A session with only
        ;; :consulted observations has no confirmed/violated/invoked.
        contributing (filter
                       (fn [b]
                         (pos? (+ (:session/confirmed    b)
                                  (:session/violated     b)
                                  (:session/invoked      b)
                                  (:session/contradicted b))))
                       buckets)]
    (when (seq contributing)
      (reduce #(if (pos? (compare %1 %2)) %1 %2) (map :session/last-at contributing)))))

(defn- decay-factor
  "0.5 ^ (days_since_last / half_life). Returns 1.0 if there is no
   last-reinforcement (nothing to decay from)."
  [rollup-map ^java.util.Date now config]
  (if-let [last-ts (last-reinforcement rollup-map)]
    (let [elapsed-days (ms->days (- (.getTime now) (.getTime ^java.util.Date last-ts)))
          half-life   (:weight/decay-half-life-days config)]
      (Math/pow 0.5 (/ elapsed-days (double half-life))))
    1.0))

(defn compute
  "Compute the weight of a card given its observation rollup, the current
   time, and the config map. Pure. Returns a non-negative double.

   A card with no observations has weight 0.0. A card whose observations
   are all `:consulted` (weight-neutral per plan) also has weight 0.0.

   Inputs:
     rollup-map  - output of `domain.rollup/rollup-by-session`
     now         - `java.util.Date`, the current time (for decay)
     config      - effective config, see `identity.config/default-config`"
  [rollup-map now config]
  (let [freq        (rollup/distinct-sessions rollup-map)
        ;; exclude :consulted from freq-for-weight
        contributing-freq (count (filter (fn [b] (pos? (+ (:session/confirmed b)
                                                          (:session/violated  b)
                                                          (:session/invoked   b)
                                                          (:session/contradicted b))))
                                         (vals rollup-map)))
        span        (temporal-span-days rollup-map)
        gaps        (gap-crossings rollup-map config)
        viol-rate   (rollup/violation-rate rollup-map)
        decay       (decay-factor rollup-map now config)
        freq-cap    (:weight/freq-cap config)
        span-exp    (:weight/span-exponent config)
        ws-penalty  (:weight/within-session-penalty config)
        viol-pen-rt (:weight/violation-penalty-rate config)]
    (if (zero? contributing-freq)
      0.0
      (let [freq-term   (min (Math/sqrt contributing-freq) freq-cap)
            span-term   (Math/pow (+ 1.0 (Math/log (+ 1.0 span))) span-exp)
            gap-term    (+ 1.0 gaps)
            within-pen  (if (zero? gaps) ws-penalty 1.0)
            base        (* freq-term span-term gap-term within-pen decay)
            penalty     (* base viol-rate viol-pen-rt)
            w           (- base penalty)]
        (max 0.0 w)))))
