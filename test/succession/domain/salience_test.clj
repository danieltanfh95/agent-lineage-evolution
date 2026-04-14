(ns succession.domain.salience-test
  (:require [clojure.test :refer [deftest is testing]]
            [succession.config :as config]
            [succession.domain.card :as card]
            [succession.domain.salience :as salience]))

(def cfg config/default-config)

(defn- a-card
  [{:keys [id tier category tags fingerprint]
    :or {category :strategy}}]
  (card/make-card
    {:id id
     :tier tier
     :category category
     :text (str "body of " id)
     :tags tags
     :fingerprint fingerprint
     :provenance {:provenance/born-at         #inst "2026-01-01T00:00:00Z"
                  :provenance/born-in-session "s0"
                  :provenance/born-from       :user-correction
                  :provenance/born-context    "ctx"}}))

(defn- scored
  ([card] (scored card 1.0 0.0))
  ([card w] (scored card w 0.0))
  ([card w rec] {:card card :weight w :recency-fraction rec}))

(deftest tier-dominates-when-nothing-else-matches-test
  (testing "with no tags/fingerprint overlap, higher tier ranks above lower"
    (let [c-principle (a-card {:id "p" :tier :principle})
          c-rule      (a-card {:id "r" :tier :rule})
          c-ethic     (a-card {:id "e" :tier :ethic})
          ranked (salience/rank
                   [(scored c-ethic) (scored c-rule) (scored c-principle)]
                   {:situation/tags [] :situation/tool-descriptor ""}
                   cfg)]
      (is (= "p" (:card/id (:card (first ranked))))))))

(deftest fingerprint-match-boosts-test
  (testing "a rule-tier card that fingerprint-matches outranks a principle-tier card that doesn't"
    (let [c-principle (a-card {:id "p" :tier :principle})
          c-rule      (a-card {:id "r" :tier :rule
                                :fingerprint "tool=Edit"})
          ranked (salience/rank
                   [(scored c-principle) (scored c-rule)]
                   {:situation/tool-descriptor "tool=Edit,target=foo.clj"}
                   cfg)]
      ;; fingerprint weight 4.0 > tier delta (3.0 * (1.0-0.6)) = 1.2
      (is (= "r" (:card/id (:card (first ranked))))))))

(deftest tag-overlap-contributes-test
  (testing "tag overlap boosts relative to a card with no overlap at the same tier"
    (let [c-a (a-card {:id "a" :tier :rule :tags [:git :safety]})
          c-b (a-card {:id "b" :tier :rule :tags [:unrelated]})
          ranked (salience/rank
                   [(scored c-b) (scored c-a)]
                   {:situation/tags [:git :safety]}
                   cfg)]
      (is (= "a" (:card/id (:card (first ranked))))))))

(deftest top-k-cap-test
  (testing "rank returns at most :top-k cards from the profile"
    (let [cards (for [i (range 10)]
                  (a-card {:id (str "c" i) :tier :rule}))
          ranked (salience/rank
                   (map scored cards)
                   {:situation/tags []}
                   cfg)]
      (is (<= (count ranked) (get-in cfg [:salience/profile :top-k]))))))

(deftest empty-input-returns-empty-test
  (is (= [] (salience/rank [] {:situation/tags []} cfg))))
