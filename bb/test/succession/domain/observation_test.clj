(ns succession.identity.domain.observation-test
  (:require [clojure.test :refer [deftest is testing]]
            [succession.identity.domain.observation :as obs]))

(defn- make-fixture
  ([] (make-fixture {}))
  ([overrides]
   (obs/make-observation
     (merge {:id      "obs-abc"
             :at      #inst "2026-04-11T12:00:00Z"
             :session "sess-xyz"
             :hook    :post-tool-use
             :source  :judge-verdict
             :card-id "prefer-edit-over-write"
             :kind    :confirmed}
            overrides))))

(deftest make-observation-test
  (testing "minimal constructor produces valid observation"
    (let [o (make-fixture)]
      (is (obs/observation? o))
      (is (= "obs-abc" (:observation/id o)))
      (is (= :confirmed (:observation/kind o)))))

  (testing "optional fields included only when present"
    (let [o (make-fixture {:context nil :judge-verdict-id nil :judge-model nil})]
      (is (not (contains? o :observation/context)))
      (is (not (contains? o :observation/judge-verdict-id)))
      (is (not (contains? o :observation/judge-model)))))

  (testing "all observation kinds are accepted"
    (doseq [k [:confirmed :violated :invoked :consulted :contradicted]]
      (is (obs/observation? (make-fixture {:kind k})))))

  (testing "invalid source rejected"
    (is (thrown? AssertionError
          (make-fixture {:source :made-up}))))

  (testing "invalid kind rejected"
    (is (thrown? AssertionError
          (make-fixture {:kind :not-a-kind}))))

  (testing "invalid hook rejected"
    (is (thrown? AssertionError
          (make-fixture {:hook :invented})))))

(deftest weight-contributing?-test
  (testing "non-consulted kinds contribute to weight"
    (doseq [k [:confirmed :violated :invoked :contradicted]]
      (is (obs/weight-contributing? (make-fixture {:kind k})))))

  (testing "consulted is weight-neutral"
    (is (not (obs/weight-contributing? (make-fixture {:kind :consulted}))))))

(deftest observation?-test
  (is (not (obs/observation? {})))
  (is (not (obs/observation? nil)))
  (is (not (obs/observation? (dissoc (make-fixture) :succession/entity-type)))))
