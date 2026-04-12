(ns succession.store.staging-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [succession.store.staging :as staging]
            [succession.store.test-helpers :as h]))

(def ^:dynamic *root* nil)

(defn with-tmp-root [t]
  (let [root (h/tmp-dir! "succession-store-staging")]
    (binding [*root* root]
      (try (t)
           (finally (h/delete-tree! root))))))

(use-fixtures :each with-tmp-root)

(defn- delta [id kind card-id]
  (staging/make-delta
    {:id id
     :at #inst "2026-04-11T12:00:00Z"
     :kind kind
     :card-id card-id
     :source :judge}))

(deftest append-and-load-deltas-test
  (testing "appended deltas read back in insertion order"
    (staging/append-delta! *root* "s1" (delta "d1" :observe-card "c1"))
    (staging/append-delta! *root* "s1" (delta "d2" :observe-card "c1"))
    (staging/append-delta! *root* "s1" (delta "d3" :create-card  nil))
    (let [loaded (staging/load-deltas *root* "s1")]
      (is (= 3 (count loaded)))
      (is (= ["d1" "d2" "d3"] (map :delta/id loaded))))))

(deftest empty-log-returns-empty-test
  (is (= [] (staging/load-deltas *root* "no-such-session")))
  (is (nil? (staging/read-snapshot *root* "no-such-session"))))

(deftest materialize-snapshot-counts-observations-test
  (testing "observation counts fold correctly across deltas"
    (staging/append-delta! *root* "s1" (delta "d1" :observe-card "c1"))
    (staging/append-delta! *root* "s1" (delta "d2" :observe-card "c1"))
    (staging/append-delta! *root* "s1" (delta "d3" :observe-card "c2"))
    (let [snap (staging/rematerialize! *root* "s1")]
      (is (= 2 (get-in snap [:staging/observation-counts "c1"])))
      (is (= 1 (get-in snap [:staging/observation-counts "c2"]))))))

(deftest snapshot-persists-and-reads-back-test
  (staging/append-delta! *root* "s1" (delta "d1" :create-card nil))
  (staging/rematerialize! *root* "s1")
  (let [snap (staging/read-snapshot *root* "s1")]
    (is (some? snap))
    (is (= 1 (count (:staging/created-cards snap))))))

(deftest make-delta-validates-test
  (testing "make-delta rejects unknown kinds"
    (is (thrown? AssertionError
                 (staging/make-delta
                   {:id "d1" :at #inst "2026-04-11T12:00:00Z"
                    :kind :bogus :source :judge})))))
