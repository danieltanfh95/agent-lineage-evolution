(ns succession.hook.pre-compact-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [succession.config :as config]
            [succession.hook.pre-compact :as pre-compact]
            [succession.store.cards :as store-cards]
            [succession.store.contradictions :as store-c]
            [succession.store.staging :as store-staging]
            [succession.store.archive :as store-archive]
            [succession.store.test-helpers :as h]))

(def ^:dynamic *root* nil)

(defn with-tmp-root [t]
  (let [root (h/tmp-dir! "succession-hook-pre-compact")]
    (binding [*root* root]
      (try (t)
           (finally (h/delete-tree! root))))))

(use-fixtures :each with-tmp-root)

(def now #inst "2026-04-11T12:00:00Z")

(deftest create-card-delta-promoted-test
  (testing "a :create-card delta produces a real card on disk after promote!"
    (store-staging/append-delta! *root* "sess1"
      (store-staging/make-delta
        {:id "d1" :at now :kind :create-card
         :payload {:id "new-card"
                   :category :strategy
                   :text "some text"
                   :provenance-context "test context"}
         :source :extract}))
    (pre-compact/promote! *root* "sess1" now config/default-config)
    (let [cards (store-cards/load-all-cards *root*)]
      (is (= 1 (count cards)))
      (is (= "new-card" (:card/id (first cards))))
      (is (= :ethic (:card/tier (first cards)))
          "new cards land at :ethic per plan"))))

(deftest update-text-delta-rewrites-test
  (testing ":update-card-text rewrites preserving id"
    (store-cards/write-card! *root*
      (h/a-card {:id "c1" :tier :rule :text "original"}))
    (store-staging/append-delta! *root* "sess1"
      (store-staging/make-delta
        {:id "d2" :at now :kind :update-card-text
         :card-id "c1"
         :payload {:text "rewritten"}
         :source :reconcile}))
    (pre-compact/promote! *root* "sess1" now config/default-config)
    (let [loaded (first (store-cards/load-all-cards *root*))]
      (is (= "c1" (:card/id loaded)))
      (is (= "rewritten" (:card/text loaded))))))

(deftest staging-cleared-after-promote-test
  (testing "clear-session! removes the staging dir"
    (store-staging/append-delta! *root* "sess-x"
      (store-staging/make-delta
        {:id "d3" :at now :kind :observe-card
         :card-id "ghost"
         :source :judge}))
    (is (store-staging/load-deltas *root* "sess-x"))
    (pre-compact/promote! *root* "sess-x" now config/default-config)
    (is (empty? (store-staging/load-deltas *root* "sess-x")))))

(deftest archive-snapshot-written-test
  (testing "promote! writes an archive snapshot of the existing promoted tree"
    (store-cards/write-card! *root*
      (h/a-card {:id "orig" :tier :rule}))
    (store-cards/materialize-promoted! *root*)
    (pre-compact/promote! *root* "sess1" now config/default-config)
    (is (seq (store-archive/list-archives *root*))
        "at least one archive directory should exist")))

(deftest llm-rewrite-applied-during-promote-test
  (testing "promote! applies pending LLM rewrites from resolved contradictions"
    (store-cards/write-card! *root*
      (h/a-card {:id "contradicted-card" :tier :rule :text "Original contradictory text"}))
    ;; Write a resolved contradiction with a pending rewrite
    (store-c/write-contradiction!
      *root*
      {:succession/entity-type    :contradiction
       :contradiction/id          "test-contra-1"
       :contradiction/at          now
       :contradiction/session     "sess1"
       :contradiction/category    :self-contradictory
       :contradiction/between     [{:card/id "contradicted-card"}]
       :contradiction/detector    :pure
       :contradiction/resolved-at now
       :contradiction/resolved-by :llm-reconcile
       :contradiction/resolution  {:category   :self-contradictory
                                   :kind       :rewrite
                                   :new-text   "Revised self-consistent text"
                                   :rationale  "Was contradictory"
                                   :confidence 0.9}
       :contradiction/escalated?  false})
    (pre-compact/promote! *root* "sess1" now config/default-config)
    (let [cards (store-cards/load-all-cards *root*)
          card  (first (filter #(= "contradicted-card" (:card/id %)) cards))]
      (is (= "Revised self-consistent text" (:card/text card))
          "LLM rewrite was applied to the card text"))))

(deftest llm-rewrite-not-reapplied-test
  (testing "promote! skips rewrites that already have :applied-at set"
    (store-cards/write-card! *root*
      (h/a-card {:id "stable-card" :tier :rule :text "Already rewritten text"}))
    (store-c/write-contradiction!
      *root*
      {:succession/entity-type    :contradiction
       :contradiction/id          "test-contra-2"
       :contradiction/at          now
       :contradiction/session     "sess1"
       :contradiction/category    :self-contradictory
       :contradiction/between     [{:card/id "stable-card"}]
       :contradiction/detector    :pure
       :contradiction/resolved-at now
       :contradiction/resolved-by :llm-reconcile
       :contradiction/resolution  {:category   :self-contradictory
                                   :kind       :rewrite
                                   :new-text   "Should not be applied again"
                                   :rationale  "Already done"
                                   :confidence 0.9
                                   :applied-at now}
       :contradiction/escalated?  false})
    (pre-compact/promote! *root* "sess1" now config/default-config)
    (let [cards (store-cards/load-all-cards *root*)
          card  (first (filter #(= "stable-card" (:card/id %)) cards))]
      (is (= "Already rewritten text" (:card/text card))
          "Already-applied rewrite was not re-applied"))))

(deftest tier-bounds-floor-holds-on-promote-test
  (testing "a card with {:floor :rule} stays at :rule even when metrics
            would demote it to :ethic (zero observations, weight=0)"
    (store-cards/write-card! *root*
      (h/a-card {:id "bounded-card" :tier :rule
                 :tier-bounds {:floor :rule}
                 :text "bounded card text"}))
    (store-cards/materialize-promoted! *root*)
    (pre-compact/promote! *root* "sess-bounds" now config/default-config)
    (let [cards (store-cards/load-all-cards *root*)
          c     (first (filter #(= "bounded-card" (:card/id %)) cards))]
      (is (some? c) "card still exists")
      (is (= :rule (:card/tier c))
          "floor held — card stays at :rule despite zero observations"))))

(deftest promoted-snapshot-regenerated-test
  (testing "promoted.edn is up-to-date after promote!"
    (store-staging/append-delta! *root* "sess1"
      (store-staging/make-delta
        {:id "d4" :at now :kind :create-card
         :payload {:id "fresh" :category :strategy :text "hi"}
         :source :extract}))
    (pre-compact/promote! *root* "sess1" now config/default-config)
    (let [snap (store-cards/read-promoted-snapshot *root*)]
      (is (= #{"fresh"} (set (map :card/id (:cards snap))))))))
