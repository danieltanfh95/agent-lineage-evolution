(ns succession.identity.domain.reconcile
  "Pure contradiction detection over identity cards + observations.

   Six contradiction categories per plan §Reconcile:

     1. Self-contradictory claim   — same card has both :confirmed and
                                     :violated in close proximity. Not
                                     an escalation; a signal to lower
                                     weight.
     2. Semantic opposition        — two cards with opposed text. LLM
                                     (llm/reconcile). NOT handled here.
     3. Observation vs card text   — a judge verdict directly negates
                                     a card. At :principle tier this is
                                     a contradiction; at lower tiers
                                     it's just a :violated observation
                                     handled in the weight pipeline.
                                     The pure detector records
                                     contradictions only when a
                                     :principle card is involved AND
                                     the observation is explicitly
                                     marked `:contradicted`.
     4. Tier violation             — declared :card/tier disagrees with
                                     eligible tier by more than the
                                     hysteresis band. Pure, recompute.
     5. Provenance conflict        — two cards born from same event.
                                     Pure, merge candidate.
     6. Contextual override        — a :violated + subsequent :confirmed
                                     pattern that suggests an evolving
                                     identity. Pure, rewrite candidate.

   Output: a seq of contradiction records (see shape below). Caller
   decides what to do — the pure detector is a finder, not an applier."
  (:require [succession.identity.domain.observation :as obs]
            [succession.identity.domain.rollup :as rollup]
            [succession.identity.domain.tier :as tier]))

(defn- make-contradiction
  [{:keys [id at session category between detector resolution]}]
  {:succession/entity-type :contradiction
   :contradiction/id       id
   :contradiction/at       at
   :contradiction/session  session
   :contradiction/category category
   :contradiction/between  between
   :contradiction/detector detector
   :contradiction/resolution resolution
   :contradiction/resolved-at nil
   :contradiction/resolved-by nil
   :contradiction/escalated?  false})

;; ------------------------------------------------------------------
;; Category 1: Self-contradictory claim
;;
;; A card whose observations include both :confirmed and :violated in
;; the same session is locally inconsistent. Not escalated; handled by
;; weight's violation penalty. We return an :uncertain marker so
;; downstream callers can act if they want.
;; ------------------------------------------------------------------
(defn detect-self-contradictory
  "Return contradiction records for cards that have :confirmed and
   :violated observations in the same session. `observations` is a seq
   keyed by card-id elsewhere; this function takes them already grouped."
  [card observations now]
  (let [per-session (rollup/rollup-by-session observations)
        conflicted-sessions
        (filter
          (fn [[_ bucket]]
            (and (pos? (:session/confirmed bucket))
                 (pos? (:session/violated  bucket))))
          per-session)]
    (for [[session _] conflicted-sessions]
      (make-contradiction
        {:id       (str "c-self-" (:card/id card) "-" session)
         :at       now
         :session  session
         :category :self-contradictory
         :between  [{:card/id (:card/id card)}]
         :detector :pure
         :resolution nil}))))

;; ------------------------------------------------------------------
;; Category 4: Tier violation
;;
;; Declared :card/tier disagrees with the eligible tier computed from
;; current metrics. This is a data-integrity contradiction, not a
;; behavior issue. Reconcile wants to know about it so PreCompact can
;; apply the correct tier.
;; ------------------------------------------------------------------
(defn detect-tier-violation
  "Given a card and its computed metrics, return a contradiction if
   the declared tier disagrees with the eligible tier. Returns a seq
   (possibly empty) for consistency with other detectors."
  [card metrics now config]
  (let [declared (:card/tier card)
        eligible (tier/eligible-tier declared metrics config)]
    (if (= declared eligible)
      []
      [(make-contradiction
         {:id         (str "c-tier-" (:card/id card))
          :at         now
          :session    nil
          :category   :tier-violation
          :between    [{:card/id (:card/id card)}]
          :detector   :pure
          :resolution {:kind :apply-tier
                       :proposed-by :pure
                       :description (format "declared %s, eligible %s"
                                            declared eligible)
                       :new-tier eligible
                       :confidence 1.0}})])))

;; ------------------------------------------------------------------
;; Category 5: Provenance conflict
;;
;; Two cards with the same :provenance/born-in-session AND identical
;; :provenance/born-context (or near-identical; we use literal equality
;; for the pure detector and defer fuzzy matching to LLM if needed).
;; Signals that the extract pass ran twice on the same event.
;; ------------------------------------------------------------------
(defn- provenance-key
  [card]
  [(get-in card [:card/provenance :provenance/born-in-session])
   (get-in card [:card/provenance :provenance/born-context])])

(defn detect-provenance-conflicts
  "Scan a seq of cards, group by (born-in-session, born-context), and
   emit a contradiction for any group with more than one card."
  [cards now]
  (->> cards
       (group-by provenance-key)
       (filter (fn [[[sess ctx] group]]
                 (and sess ctx (> (count group) 1))))
       (map
         (fn [[[sess _ctx] group]]
           (make-contradiction
             {:id         (str "c-prov-" sess "-" (hash (map :card/id group)))
              :at         now
              :session    sess
              :category   :provenance-conflict
              :between    (mapv #(-> {:card/id (:card/id %)}) group)
              :detector   :pure
              :resolution {:kind :merge-candidates
                           :proposed-by :pure
                           :description "identical born-session + born-context"
                           :confidence 0.9}})))))

;; ------------------------------------------------------------------
;; Category 6: Contextual override
;;
;; A :violated observation followed in a later session by a :confirmed
;; observation on the same card, where the violation is the most recent
;; in its own session and no other violations follow. Pattern: "user
;; said no, then we did the new thing, and it worked." The card may
;; need its text rewritten to reflect the evolved behavior — but the
;; pure detector only flags the candidate, the LLM extract can propose
;; the rewrite.
;; ------------------------------------------------------------------
(defn detect-contextual-override
  "Look for a card-observation sequence that looks like evolving
   identity: violated in session N, confirmed in session N+1 or later
   with no further violations."
  [card observations now]
  (let [ordered (sort-by :observation/at observations)
        ;; partition into sessions for analysis
        by-session (group-by :observation/session ordered)
        sessions-in-order
        (distinct (map :observation/session ordered))]
    (if (<= (count sessions-in-order) 1)
      []
      (let [violated-sessions (filter
                                (fn [s]
                                  (some #(= :violated (:observation/kind %))
                                        (get by-session s)))
                                sessions-in-order)
            later-all-confirmed?
            (when-let [last-violated (last violated-sessions)]
              (let [idx (.indexOf ^clojure.lang.PersistentVector
                                  (vec sessions-in-order)
                                  last-violated)
                    later (drop (inc idx) sessions-in-order)]
                (and (seq later)
                     (every? (fn [s]
                               (let [obs (get by-session s)]
                                 (and (some #(= :confirmed (:observation/kind %)) obs)
                                      (not-any? #(= :violated (:observation/kind %)) obs))))
                             later))))]
        (if later-all-confirmed?
          [(make-contradiction
             {:id         (str "c-override-" (:card/id card))
              :at         now
              :session    (last sessions-in-order)
              :category   :contextual-override
              :between    [{:card/id (:card/id card)}]
              :detector   :pure
              :resolution {:kind :rewrite-candidate
                           :proposed-by :pure
                           :description "violation followed by sustained confirmation — identity may have evolved"
                           :confidence 0.7}})]
          [])))))

;; ------------------------------------------------------------------
;; Combined pure pass
;;
;; Runs all pure detectors over a seq of cards and their observations.
;; Returns a seq of contradictions. Caller supplies `metrics-fn` that
;; maps card-id → {:weight :violation-rate :gap-crossings}, so weight
;; (which needs `now` and config) doesn't leak into this pure module.
;; ------------------------------------------------------------------
(defn detect-all
  "Run all pure-category detectors over a snapshot:

     cards          - seq of identity cards
     observations-by-card - map card-id → seq of observations
     metrics-by-card      - map card-id → {:weight :violation-rate :gap-crossings}
     now                  - java.util.Date
     config               - effective config"
  [cards observations-by-card metrics-by-card now config]
  (concat
    ;; Category 1: self-contradictory
    (mapcat
      (fn [card]
        (detect-self-contradictory
          card (get observations-by-card (:card/id card) []) now))
      cards)
    ;; Category 4: tier violation
    (mapcat
      (fn [card]
        (if-let [m (get metrics-by-card (:card/id card))]
          (detect-tier-violation card m now config)
          []))
      cards)
    ;; Category 5: provenance conflict
    (detect-provenance-conflicts cards now)
    ;; Category 6: contextual override
    (mapcat
      (fn [card]
        (detect-contextual-override
          card (get observations-by-card (:card/id card) []) now))
      cards)))
