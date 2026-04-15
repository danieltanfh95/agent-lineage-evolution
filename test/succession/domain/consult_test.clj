(ns succession.domain.consult-test
  (:require [clojure.test :refer [deftest is testing]]
            [succession.config :as config]
            [succession.domain.card :as card]
            [succession.domain.consult :as consult]))

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

(defn- scored [card] {:card card :weight 5.0 :recency-fraction 0.5})

(deftest principle-forbids-tension-test
  (testing "a principle card that fingerprint-matches the situation emits a :principle-forbids tension"
    (let [c (a-card {:id "never-force-push-main" :tier :principle
                     :fingerprint "tool=Bash,cmd=git push --force"})
          result (consult/query
                   [(scored c)]
                   {:situation/text "about to force-push"
                    :situation/tool-descriptor "tool=Bash,cmd=git push --force origin main"}
                   cfg)
          tensions (:consult/tensions result)]
      (is (= 1 (count tensions)))
      (is (= :principle-forbids (:tension/kind (first tensions))))
      (is (= "never-force-push-main" (:card/id (:tension/card (first tensions))))))))

(deftest tier-split-tension-test
  (testing "two cards in the same category at :principle and :ethic emit a :tier-split tension"
    (let [c1 (a-card {:id "c1" :tier :principle :category :strategy
                       :tags [:git]})
          c2 (a-card {:id "c2" :tier :ethic :category :strategy
                       :tags [:git]})
          result (consult/query
                   [(scored c1) (scored c2)]
                   {:situation/tags [:git]}
                   cfg)
          tensions (:consult/tensions result)]
      ;; both should be present in the candidate set because tags overlap
      (is (some #(= :tier-split (:tension/kind %)) tensions)))))

(deftest contradiction-adjacent-test
  (testing "an open contradiction against a candidate card yields a :contradiction-adjacent tension"
    (let [c (a-card {:id "c1" :tier :rule :tags [:git]})
          result (consult/query
                   [(scored c)]
                   {:situation/tags [:git]
                    :situation/contradictions
                    [{:contradiction/between [{:card/id "c1"}]
                      :contradiction/category :self-contradictory}]}
                   cfg)
          tensions (:consult/tensions result)]
      (is (some #(= :contradiction-adjacent (:tension/kind %)) tensions)))))

(deftest groups-and-cap-test
  (testing "query returns candidates grouped by tier and by category, capped at top-k"
    (let [cards (for [i (range 10)]
                  (a-card {:id (str "c" i) :tier :rule}))
          result (consult/query
                   (mapv scored cards)
                   {:situation/tags []}
                   cfg)]
      (is (<= (count (:consult/candidates result))
              (get-in cfg [:salience/profile :top-k])))
      (is (= #{:rule} (set (keys (:consult/by-tier result)))))
      (is (pos? (count (get (:consult/by-tier result) :rule))))
      (is (= (get-in cfg [:salience/profile :top-k])
             (:consult/top-k result))))))

(deftest empty-situation-test
  (testing "an empty candidate pool returns no candidates and no tensions"
    (let [result (consult/query [] {:situation/text "hello"} cfg)]
      (is (empty? (:consult/candidates result)))
      (is (empty? (:consult/tensions result))))))
