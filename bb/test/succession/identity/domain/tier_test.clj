(ns succession.identity.domain.tier-test
  (:require [clojure.test :refer [deftest is testing]]
            [succession.identity.config :as config]
            [succession.identity.domain.card :as card]
            [succession.identity.domain.tier :as tier]))

(def cfg config/default-config)

(defn- a-card [tier]
  (card/make-card
    {:id         "test-card"
     :tier       tier
     :category   :strategy
     :text       "test"
     :provenance {:provenance/born-at         #inst "2026-01-01T00:00:00Z"
                  :provenance/born-in-session "s0"
                  :provenance/born-from       :user-correction
                  :provenance/born-context    "test"}}))

(defn- metrics
  [w vr gc]
  {:weight w :violation-rate vr :gap-crossings gc})

(deftest eligible-principle-test
  (testing "high weight + zero violations + enough crossings → principle"
    (is (= :principle
           (tier/eligible-tier :rule (metrics 35.0 0.0 5) cfg)))))

(deftest eligible-rule-test
  (testing "moderate weight + low violations → rule (not principle)"
    (is (= :rule
           (tier/eligible-tier :ethic (metrics 10.0 0.1 1) cfg)))))

(deftest eligible-ethic-floor-test
  (testing "very low weight, single observation → ethic"
    (is (= :ethic
           (tier/eligible-tier :ethic (metrics 1.0 0.0 0) cfg)))))

(deftest hysteresis-no-flicker-test
  (testing "a card currently at :principle with weight in the hysteresis band stays"
    ;; Enter-principle requires weight ≥ 30, exit triggers at ≤ 20.
    ;; Weight 25 is in the band: the card should NOT demote to :rule
    ;; just because it no longer meets the *enter* threshold.
    (is (= :principle
           (tier/eligible-tier :principle (metrics 25.0 0.0 5) cfg))
        "weight 25 is between exit (20) and enter (30) — principle holds"))
  (testing "but a card currently at :rule with weight 25 does NOT promote"
    ;; The enter threshold for :principle is 30. A rule-tier card
    ;; needs to cross 30 to enter principle, even though a principle-tier
    ;; card at 25 would stay. This is the hysteresis band doing its job.
    (is (= :rule
           (tier/eligible-tier :rule (metrics 25.0 0.0 5) cfg))
        "weight 25 is below the enter threshold for :principle")))

(deftest demotion-on-violation-test
  (testing "a :principle card with violations above exit threshold demotes"
    ;; Exit triggers at :min-violation-rate 0.1 — OR semantics, violation
    ;; rate alone is enough to demote.
    (is (= :rule
           (tier/eligible-tier :principle (metrics 40.0 0.15 5) cfg))
        "violation rate 0.15 > 0.1 triggers principle exit"))
  (testing "a :rule card with heavy violations demotes to ethic"
    (is (= :ethic
           (tier/eligible-tier :rule (metrics 10.0 0.6 2) cfg))
        "violation rate 0.6 > 0.5 triggers rule exit")))

(deftest propose-transition-test
  (let [c (a-card :rule)]
    (testing "promotion from rule to principle"
      (let [t (tier/propose-transition c (metrics 35.0 0.0 5) cfg)]
        (is (= :promote (:kind t)))
        (is (= :rule (:from t)))
        (is (= :principle (:to t)))))

    (testing "no-op when already at eligible tier"
      (let [t (tier/propose-transition c (metrics 10.0 0.1 1) cfg)]
        (is (= :no-op (:kind t)))
        (is (= :rule (:from t)))
        (is (= :rule (:to t)))))

    (testing "demotion on violation"
      (let [c' (a-card :principle)
            t  (tier/propose-transition c' (metrics 40.0 0.15 5) cfg)]
        (is (= :demote (:kind t)))
        (is (= :principle (:from t)))
        (is (= :rule (:to t)))))))

(deftest archive-test
  (testing "an :ethic card below archive floor proposes :archive"
    (let [c (a-card :ethic)
          t (tier/propose-transition c (metrics 0.3 0.0 0) cfg)]
      (is (= :archive (:kind t)))
      (is (= :ethic (:from t)))
      (is (= :archived (:to t))))))

(deftest demote-before-promote-test
  (testing "a tier transition does not simultaneously demote and promote"
    ;; A principle card with high weight but an unmet min-gap-crossings
    ;; should still stay at principle (not demote to rule then promote
    ;; back). This tests the rule that demotion only triggers on exit
    ;; conditions, not on failure-to-meet enter conditions.
    (is (= :principle
           (tier/eligible-tier :principle (metrics 35.0 0.0 3) cfg))
        "min-gap-crossings 3 < 5 doesn't trigger principle exit since weight is fine")))
