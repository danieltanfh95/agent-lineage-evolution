(ns succession.llm.reconcile-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [succession.config :as config]
            [succession.domain.card :as card]
            [succession.domain.observation :as obs]
            [succession.llm.reconcile :as reconcile]))

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

(defn- an-obs
  ([id at kind] (an-obs id at kind "c1"))
  ([id at kind card-id]
   (obs/make-observation
     {:id id :at at :session "s1" :hook :post-tool-use
      :source :judge-verdict :card-id card-id :kind kind})))

(deftest category-2-prompt-includes-both-cards-test
  (let [prompt (reconcile/build-category-2-prompt
                 {:card-a (a-card "ca" :rule)
                  :card-b (a-card "cb" :rule)
                  :obs-a  [(an-obs "o1" #inst "2026-04-01T10:00:00Z" :confirmed "ca")]
                  :obs-b  [(an-obs "o2" #inst "2026-04-02T10:00:00Z" :confirmed "cb")]})]
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

(deftest category-1-prompt-includes-card-test
  (let [prompt (reconcile/build-category-1-prompt
                 {:card (a-card "verify-via-repl" :principle)})]
    (is (str/includes? prompt "verify-via-repl"))
    (is (str/includes? prompt "CARD"))
    (is (str/includes? prompt "self-consistent"))
    (is (str/includes? prompt "self-contradictory"))))

(deftest category-6-prompt-includes-card-and-pattern-test
  (let [prompt (reconcile/build-category-6-prompt
                 {:card (a-card "data-first-design" :rule)
                  :prior-description "Rule is skipped when in plan mode"})]
    (is (str/includes? prompt "data-first-design"))
    (is (str/includes? prompt "Rule is skipped when in plan mode"))
    (is (str/includes? prompt "scope-qualify"))
    (is (str/includes? prompt "intentional"))))

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
    (is (not (reconcile/auto-applicable? nil cfg))))
  (testing "auto-applicable at exactly the threshold"
    (is (reconcile/auto-applicable?
          {:category :semantic-opposition :kind :scope-partition
           :confidence 0.8 :rationale "" :payload {}}
          cfg))))
