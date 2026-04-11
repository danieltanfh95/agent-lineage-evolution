(ns succession.identity.hook.user-prompt-submit-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [succession.identity.config :as config]
            [succession.identity.hook.user-prompt-submit :as ups]
            [succession.identity.store.staging :as store-staging]
            [succession.identity.store.test-helpers :as h]))

(def ^:dynamic *root* nil)

(defn with-tmp-root [t]
  (let [root (h/tmp-dir! "succession-hook-ups")]
    (binding [*root* root]
      (try (t)
           (finally (h/delete-tree! root))))))

(use-fixtures :each with-tmp-root)

(def now #inst "2026-04-11T12:00:00Z")

(deftest detect-correction-patterns-test
  (testing "regex patterns match common correction phrasings"
    (let [patterns (:correction/patterns config/default-config)]
      (is (some? (ups/detect-correction "no, use the other function" patterns)))
      (is (some? (ups/detect-correction "stop looping over it" patterns)))
      (is (some? (ups/detect-correction "don't edit that file" patterns)))
      (is (some? (ups/detect-correction "actually, use Grep" patterns)))
      (is (some? (ups/detect-correction "that's wrong" patterns))))))

(deftest no-correction-on-normal-prompt-test
  (testing "a benign prompt does not match"
    (let [patterns (:correction/patterns config/default-config)]
      (is (nil? (ups/detect-correction "please implement the tests" patterns)))
      (is (nil? (ups/detect-correction "how does the weight formula work?" patterns))))))

(deftest handle-prompt-stages-correction-delta-test
  (testing "a matched correction writes a :mark-contradiction delta"
    (let [hit (ups/handle-prompt! *root* "sess1"
                                  "no, use Grep instead"
                                  now config/default-config)]
      (is (some? hit))
      (let [deltas (store-staging/load-deltas *root* "sess1")]
        (is (some #(= :mark-contradiction (:delta/kind %)) deltas))
        (is (some #(= :user-correction (:delta/source %)) deltas))))))

(deftest no-match-no-delta-test
  (testing "benign prompts leave staging empty"
    (ups/handle-prompt! *root* "sess1" "implement the hooks" now config/default-config)
    (is (empty? (store-staging/load-deltas *root* "sess1")))))
