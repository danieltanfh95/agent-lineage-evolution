(ns succession.identity.llm.reconcile-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [succession.identity.config :as config]
            [succession.identity.domain.card :as card]
            [succession.identity.domain.observation :as obs]
            [succession.identity.llm.reconcile :as reconcile]))

(def cfg config/default-config)

(defn- a-card [id tier]
  (card/make-card
    {:id id
     :tier tier
     :category :strategy
     :text (str "body of " id)
     :tags [:git]
     :provenance {:provenance/born-at         #inst "2026-01-01T00:00:00Z"
                  :provenance/born-in-session "s0"
                  :provenance/born-from       :user-correction
                  :provenance/born-context    "ctx"}}))

(defn- an-obs [id at kind]
  (obs/make-observation
    {:id id :at at :session "s1" :hook :post-tool-use
     :source :judge-verdict :card-id "c1" :kind kind}))

(deftest category-2-prompt-includes-both-cards-test
  (let [prompt (reconcile/build-category-2-prompt
                 {:card-a (a-card "ca" :rule)
                  :card-b (a-card "cb" :rule)
                  :obs-a  [(an-obs "o1" #inst "2026-04-01T10:00:00Z" :confirmed)]
                  :obs-b  [(an-obs "o2" #inst "2026-04-02T10:00:00Z" :confirmed)]})]
    (is (str/includes? prompt "ca"))
    (is (str/includes? prompt "cb"))
    (is (str/includes? prompt "scope-partition"))
    (is (str/includes? prompt "CARD A"))
    (is (str/includes? prompt "CARD B"))))

(deftest category-3-principle-prompt-test
  (let [prompt (reconcile/build-category-3-principle-prompt
                 {:card (a-card "never-force-push" :principle)
                  :violating-observation (an-obs "o1" #inst "2026-04-11T12:00:00Z" :violated)
                  :obs-history [(an-obs "o0" #inst "2026-04-10T10:00:00Z" :confirmed)]})]
    (is (str/includes? prompt "PRINCIPLE CARD"))
    (is (str/includes? prompt "never-force-push"))
    (is (str/includes? prompt "violated"))
    (is (str/includes? prompt ":rewrite"))
    (is (str/includes? prompt ":demote"))
    (is (str/includes? prompt ":escalate"))))

(deftest parse-response-object-test
  (let [text "{\"category\":\"semantic-opposition\",
               \"kind\":\"scope-partition\",
               \"scope_a\":\"file ext != .config\",
               \"scope_b\":\"file ext == .config\",
               \"winner_card_id\":\"ca\",
               \"loser_card_id\":\"cb\",
               \"rationale\":\"config files use Write\",
               \"confidence\":0.92}"
        res (reconcile/parse-response text)]
    (is (= :semantic-opposition (:category res)))
    (is (= :scope-partition (:kind res)))
    (is (= 0.92 (:confidence res)))
    (is (= "ca" (get-in res [:payload :winner_card_id])))))

(deftest parse-response-garbage-returns-nil-test
  (is (nil? (reconcile/parse-response "not json")))
  (is (nil? (reconcile/parse-response nil)))
  (is (nil? (reconcile/parse-response "{\"no-category\":true}"))))

(deftest auto-applicable-confidence-threshold-test
  (testing "auto-applicable when confidence >= threshold"
    (is (reconcile/auto-applicable?
          {:category :semantic-opposition :kind :scope-partition
           :confidence 0.85 :rationale "" :payload {}}
          cfg)))
  (testing "not auto-applicable below threshold"
    (is (not (reconcile/auto-applicable?
               {:category :semantic-opposition :kind :scope-partition
                :confidence 0.5 :rationale "" :payload {}}
               cfg))))
  (testing "nil resolution never auto-applicable"
    (is (not (reconcile/auto-applicable? nil cfg)))))
