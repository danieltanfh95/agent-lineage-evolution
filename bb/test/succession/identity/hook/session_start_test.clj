(ns succession.identity.hook.session-start-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [succession.identity.hook.session-start :as ss]
            [succession.identity.store.staging :as store-staging]
            [succession.identity.store.test-helpers :as h]))

(def ^:dynamic *root* nil)

(defn with-tmp-root [t]
  (let [root (h/tmp-dir! "succession-hook-session-start")]
    (binding [*root* root]
      (try (t)
           (finally (h/delete-tree! root))))))

(use-fixtures :each with-tmp-root)

(def now #inst "2026-04-11T12:00:00Z")

(deftest empty-identity-renders-placeholder-test
  (testing "no cards yet — context still has the footer"
    (let [ctx (ss/build-context [] [])]
      (is (str/includes? ctx "No promoted identity cards yet"))
      (is (str/includes? ctx "bb succession consult"))
      (is (str/includes? ctx "succession-consult")))))

(deftest cards-rendered-by-tier-test
  (testing "cards show up under their tier header"
    (let [scored [{:card (h/a-card {:id "p1" :tier :principle})
                   :weight 42.0 :recency-fraction 1.0}
                  {:card (h/a-card {:id "r1" :tier :rule})
                   :weight 6.0 :recency-fraction 0.9}]
          ctx (ss/build-context scored [])]
      (is (str/includes? ctx "Principle"))
      (is (str/includes? ctx "Rule"))
      (is (str/includes? ctx "p1"))
      (is (str/includes? ctx "r1")))))

(deftest orphan-note-emitted-test
  (testing "orphan staging sessions surface in the returned markdown"
    (let [ctx (ss/build-context [] ["abandoned-sess-1" "abandoned-sess-2"])]
      (is (str/includes? ctx "Pending reconciliation"))
      (is (str/includes? ctx "abandoned-sess-1"))
      (is (str/includes? ctx "abandoned-sess-2")))))

(deftest no-orphans-no-note-test
  (testing "empty orphans seq produces no note (clean session case)"
    (let [ctx (ss/build-context [] [])]
      (is (not (str/includes? ctx "Pending reconciliation"))))))
