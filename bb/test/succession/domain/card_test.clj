(ns succession.domain.card-test
  (:require [clojure.test :refer [deftest is testing]]
            [succession.domain.card :as card]))

(def fixture-provenance
  {:provenance/born-at         #inst "2026-01-15T10:23:00Z"
   :provenance/born-in-session "abc123"
   :provenance/born-from       :user-correction
   :provenance/born-context    "user said 'use Edit, not Write'"})

(defn- make-fixture
  ([] (make-fixture {}))
  ([overrides]
   (card/make-card
     (merge {:id         "prefer-edit-over-write"
             :tier       :rule
             :category   :strategy
             :text       "Prefer Edit over Write when modifying existing files."
             :tags       [:file-editing :tooling]
             :fingerprint "tool=Edit,precondition=existing-file"
             :provenance fixture-provenance}
            overrides))))

(deftest make-card-test
  (testing "constructor produces a valid card"
    (let [c (make-fixture)]
      (is (card/card? c))
      (is (= "prefer-edit-over-write" (:card/id c)))
      (is (= :rule  (:card/tier c)))
      (is (= :strategy (:card/category c)))
      (is (= fixture-provenance (:card/provenance c)))))

  (testing "optional fields are included only when present"
    (let [c (card/make-card {:id "x"
                             :tier :ethic
                             :category :relational-calibration
                             :text "..."
                             :provenance fixture-provenance})]
      (is (card/card? c))
      (is (not (contains? c :card/tags)))
      (is (not (contains? c :card/fingerprint)))))

  (testing "invalid tier is rejected"
    (is (thrown? AssertionError
          (card/make-card {:id "x" :tier :not-a-tier :category :strategy
                           :text "..." :provenance fixture-provenance}))))

  (testing "invalid category is rejected"
    (is (thrown? AssertionError
          (card/make-card {:id "x" :tier :rule :category :nope
                           :text "..." :provenance fixture-provenance})))))

(deftest card?-test
  (testing "rejects random maps"
    (is (not (card/card? {:id "x"})))
    (is (not (card/card? nil)))
    (is (not (card/card? "string"))))

  (testing "rejects cards missing entity type"
    (is (not (card/card? (dissoc (make-fixture) :succession/entity-type))))))

(deftest rewrite-test
  (testing "rewrite preserves provenance and appends to :card/rewrites"
    (let [c  (make-fixture)
          c' (card/rewrite c "Updated text." "sha-of-old")]
      (is (= "Updated text." (:card/text c')))
      (is (= fixture-provenance (:card/provenance c')))
      (is (= ["sha-of-old"] (:card/rewrites c')))
      (let [c'' (card/rewrite c' "Second update." "sha-of-mid")]
        (is (= ["sha-of-old" "sha-of-mid"] (:card/rewrites c'')))))))

(deftest retier-test
  (testing "retier updates tier only"
    (let [c  (make-fixture)
          c' (card/retier c :principle)]
      (is (= :principle (:card/tier c')))
      (is (= (:card/text c) (:card/text c')))
      (is (card/card? c')))))

(deftest fingerprint-test
  (is (card/has-fingerprint? (make-fixture)))
  (is (not (card/has-fingerprint? (dissoc (make-fixture) :card/fingerprint)))))
