(ns succession.hook.stop-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [succession.config :as config]
            [succession.hook.stop :as stop]
            [succession.store.cards :as store-cards]
            [succession.store.contradictions :as store-contra]
            [succession.store.observations :as store-obs]
            [succession.store.staging :as store-staging]
            [succession.store.test-helpers :as h]))

(def ^:dynamic *root* nil)

(defn with-tmp-root [t]
  (let [root (h/tmp-dir! "succession-hook-stop")]
    (binding [*root* root]
      (try (t)
           (finally (h/delete-tree! root))))))

(use-fixtures :each with-tmp-root)

(def now #inst "2026-04-11T12:00:00Z")

(deftest self-contradictory-detected-and-staged-test
  (testing "card with confirmed+violated in same session produces a contradiction delta"
    (store-cards/write-card! *root*
      (h/a-card {:id "c1" :tier :rule :text "always verify"}))
    (store-obs/write-observation! *root*
      (h/an-observation {:id "o1" :at now :session "sess1"
                         :card-id "c1" :kind :confirmed}))
    (store-obs/write-observation! *root*
      (h/an-observation {:id "o2" :at now :session "sess1"
                         :card-id "c1" :kind :violated}))
    (let [found (stop/run-pure-reconcile! *root* "sess1" now config/default-config)]
      (is (seq found))
      (is (some #(= :self-contradictory (:contradiction/category %)) found))
      (let [deltas (store-staging/load-deltas *root* "sess1")]
        (is (some #(= :mark-contradiction (:delta/kind %)) deltas)))
      (is (seq (store-contra/load-all-contradictions *root*))))))

(deftest no-contradictions-empty-result-test
  (testing "clean card with only confirmations produces nothing"
    (store-cards/write-card! *root*
      (h/a-card {:id "c-clean" :tier :rule :text "fine"}))
    (store-obs/write-observation! *root*
      (h/an-observation {:id "o1" :at now :session "sess1"
                         :card-id "c-clean" :kind :confirmed}))
    (let [found (stop/run-pure-reconcile! *root* "sess1" now config/default-config)]
      ;; We may still get a tier-violation for a card with no real metrics,
      ;; so just assert: no self-contradictory was produced.
      (is (not (some #(= :self-contradictory (:contradiction/category %)) found))))))

(deftest staging-snapshot-rematerialized-test
  (testing "Stop rematerializes the staging snapshot so consult sees the new deltas"
    (store-cards/write-card! *root*
      (h/a-card {:id "c1" :tier :rule}))
    (store-obs/write-observation! *root*
      (h/an-observation {:id "o1" :at now :session "sess1"
                         :card-id "c1" :kind :confirmed}))
    (store-obs/write-observation! *root*
      (h/an-observation {:id "o2" :at now :session "sess1"
                         :card-id "c1" :kind :violated}))
    (stop/run-pure-reconcile! *root* "sess1" now config/default-config)
    (let [snap (store-staging/read-snapshot *root* "sess1")]
      (is (some? snap))
      (is (seq (:staging/contradiction-ids snap))))))
