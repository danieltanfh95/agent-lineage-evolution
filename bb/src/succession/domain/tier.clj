(ns succession.domain.tier
  "Pure tier promotion/demotion with hysteresis.

   Three tiers per plan §Three tiers:
     :principle - inviolable, from 原則
     :rule      - breakable with justification, from 規則
     :ethic     - aspirational niceties, from 倫理

   The tier a card *is* (declared in `:card/tier`) may disagree with the
   tier it's *eligible* for given its current weight, violation rate,
   and gap-crossings. This namespace computes the eligible tier and
   proposes a transition, honouring hysteresis bands so a card near a
   threshold does not flicker across tiers as observations arrive.

   Hysteresis rule: a card *enters* a higher tier when the `:enter`
   thresholds are met, and *exits* that tier only when the `:exit`
   thresholds are crossed. The gap between enter and exit is the
   hysteresis band. This is configured entirely via
   `(:tier/rules config)` — code here is a rule interpreter, not a
   value hardcoder.

   No I/O. Takes config as input. Returns proposed transitions; caller
   decides whether to apply (PreCompact does).

   Reference: `.plans/succession-identity-cycle.md` §Tier thresholds."
  (:require [succession.config :as config]))

(def ^:private tier-order
  "Higher index = more load-bearing."
  [:ethic :rule :principle])

(defn- tier-rank [tier] (.indexOf ^clojure.lang.PersistentVector tier-order tier))

(defn- meets-enter?
  "Does the card meet the enter-thresholds for a tier? `metrics` is
   a map of `{:weight :violation-rate :gap-crossings}`."
  [enter-rules {:keys [weight violation-rate gap-crossings]}]
  (and
    (>= weight         (get enter-rules :min-weight         0.0))
    (<= violation-rate (get enter-rules :max-violation-rate 1.0))
    (>= gap-crossings  (get enter-rules :min-gap-crossings  0))))

(defn- triggers-exit?
  "Does the card trigger the exit-thresholds for its current tier?
   Cards exit when ANY exit condition is met — exit is OR, enter is AND."
  [exit-rules {:keys [weight violation-rate]}]
  (or
    (and (contains? exit-rules :max-weight)
         (<= weight (:max-weight exit-rules)))
    (and (contains? exit-rules :min-violation-rate)
         (>= violation-rate (:min-violation-rate exit-rules)))))

(defn- below-archive?
  "Is the card below the ethic floor (archive candidate)?"
  [config metrics]
  (let [rules (get-in config [:tier/rules :ethic :exit])]
    (and (contains? rules :archive-below-weight)
         (< (:weight metrics) (:archive-below-weight rules)))))

(defn eligible-tier
  "Given the card's current tier and metrics, return the tier the card
   SHOULD be in according to the hysteresis rules.

   Rules in priority order:
     1. If the card's current tier triggers its exit rules, demote one
        step down (principle → rule, rule → ethic).
     2. Else try to promote: scan tiers above current, pick the highest
        whose enter rules are met.
     3. Else stay.

   Demotion runs before promotion in the same tick so a card cannot
   simultaneously promote and demote."
  [current-tier metrics config]
  (let [tier-rules (:tier/rules config)
        current-rank (tier-rank current-tier)
        demoted? (and (pos? current-rank)
                      (triggers-exit?
                        (get-in tier-rules [current-tier :exit])
                        metrics))
        tier-after-demote (if demoted?
                            (nth tier-order (dec current-rank))
                            current-tier)
        rank-after (tier-rank tier-after-demote)
        ;; Try promotion from the tier-after-demote position, scanning upward.
        higher-candidates (drop (inc rank-after) tier-order)
        promotion (last  ; highest reachable tier
                    (filter
                      #(meets-enter?
                         (get-in tier-rules [% :enter])
                         metrics)
                      higher-candidates))]
    (or promotion tier-after-demote)))

(defn propose-transition
  "High-level: return a map describing any change the card needs.

     {:card/id       ...
      :from          :rule
      :to            :principle
      :kind          :promote    ; or :demote, :archive, :no-op
      :reason        \"met enter rules for :principle\"
      :metrics       {...}}

   `card` must include `:card/id` and `:card/tier`. `metrics` is the
   `{:weight :violation-rate :gap-crossings}` for the card."
  [card metrics config]
  (let [id           (:card/id card)
        current      (:card/tier card)
        current-rank (tier-rank current)
        eligible     (eligible-tier current metrics config)
        archive?     (and (= :ethic current) (below-archive? config metrics))
        transition   {:card/id id
                      :from    current
                      :to      (if archive? :archived eligible)
                      :metrics metrics}]
    (cond
      archive?
      (assoc transition :kind :archive :reason "below ethic archive threshold")

      (= eligible current)
      (assoc transition :kind :no-op :reason "already at eligible tier")

      (> (tier-rank eligible) current-rank)
      (assoc transition :kind :promote :reason (format "met enter rules for %s" eligible))

      (< (tier-rank eligible) current-rank)
      (assoc transition :kind :demote  :reason (format "triggered exit rules from %s" current)))))
