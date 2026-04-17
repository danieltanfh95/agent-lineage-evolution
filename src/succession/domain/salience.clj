(ns succession.domain.salience
  "Pure ranking of cards against a situation.

   PreToolUse and PostToolUse refresh both need to answer: 'given this
   tool call (and its inputs/outputs), which N cards are most relevant
   to remind the agent about?' That is salience ranking. It is pure —
   no LLM, no I/O — and tunable via `:salience/profile` in config.

   The score for a card against a situation is a weighted sum of
   feature contributions. Features:

     :tier-weight  - higher tier = more important to surface
     :tag-match    - card tags overlapping the situation's tags
     :fingerprint  - card fingerprint matches the tool name + inputs
     :recency      - more recently observed cards bubble up slightly
     :weight       - card's overall weight contributes to baseline

   Each feature's weight is data in `(get-in config [:salience/profile :feature-weights])`.

   The function takes the cards already paired with their precomputed
   weights — keeping `domain/weight` separate. Recency is also passed
   in (computed by the caller from observations) so this namespace
   stays free of clock-reading."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [succession.config :as config]))

(def ^:private tier-baseline
  "Constant per-tier baseline used by the :tier-weight feature.
   Principle is most surfaceable, ethic least."
  {:principle 1.0
   :rule      0.6
   :ethic     0.3})

(defn- normalize [x cap]
  (if (or (nil? x) (nil? cap) (zero? cap)) 0.0 (min 1.0 (/ (double x) (double cap)))))

(defn- tag-overlap
  "Number of tags shared between the card and the situation, divided
   by the smaller set's size, clamped to [0, 1]."
  [card-tags situation-tags]
  (let [a (set card-tags)
        b (set situation-tags)
        denom (min (count a) (count b))]
    (if (zero? denom)
      0.0
      (/ (count (set/intersection a b)) (double denom)))))

(defn- fingerprint-match?
  "True if the card's :card/fingerprint string is a substring match
   against the situation's tool descriptor OR recent context. The
   descriptor is built by the caller as a stable string like
   'tool=Edit,target=file.ts'. Recent context contains the user's
   message and assistant response, enabling intent-based matching
   (e.g. fingerprint 'release' matches user saying 'push release')."
  [card situation]
  (if-let [fp (:card/fingerprint card)]
    (let [descriptor (or (:situation/tool-descriptor situation) "")
          context    (or (:situation/recent-context situation) "")]
      (or (str/includes? descriptor fp)
          (str/includes? context fp)))
    false))

(defn- score-card
  "Pure score = sum (feature-weight * feature-value) for one card."
  [{:keys [card weight recency-fraction]} situation profile]
  (let [fw (:feature-weights profile)]
    (+ (* (:tier-weight fw 0.0) (get tier-baseline (:card/tier card) 0.0))
       (* (:tag-match   fw 0.0) (tag-overlap (:card/tags card) (:situation/tags situation)))
       (* (:fingerprint fw 0.0) (if (fingerprint-match? card situation) 1.0 0.0))
       (* (:recency     fw 0.0) (or recency-fraction 0.0))
       (* (:weight      fw 0.0) (normalize weight 50.0)))))

(defn rank
  "Rank cards by salience for a given situation. Returns the cards in
   descending score order, capped at top-K from the salience profile.

   Inputs:
     scored-cards - seq of `{:card <card> :weight <num> :recency-fraction <num>}`
                    where recency-fraction is 0..1 (1 = just-touched, 0 = ancient)
     situation    - map with optional `:situation/tags` (seq) and
                    `:situation/tool-descriptor` (string)
     config       - effective config

   Returns: a vector of `{:card ... :score ...}` ordered desc, length ≤ top-K."
  [scored-cards situation config]
  (let [profile (:salience/profile config)
        top-k   (:top-k profile)]
    (->> scored-cards
         (map (fn [m] {:card  (:card m)
                       :score (score-card m situation profile)}))
         (sort-by :score (fn [a b] (compare b a)))
         (take top-k)
         vec)))
