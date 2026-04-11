(ns succession.identity.cli.identity-diff-test
  (:require [clojure.test :refer [deftest is testing]]
            [succession.identity.cli.identity-diff :as idiff]
            [succession.identity.store.test-helpers :as h]))

(deftest diff-empty-empty-test
  (is (= {:added () :removed () :retiered () :rewritten ()}
         (idiff/diff-cards {} {}))))

(deftest diff-additions-test
  (let [before {}
        after  {"a" (h/a-card {:id "a" :tier :rule :text "t"})}
        d      (idiff/diff-cards before after)]
    (is (= '("a") (:added d)))
    (is (empty? (:removed d)))))

(deftest diff-removals-test
  (let [before {"a" (h/a-card {:id "a" :tier :rule})}
        after  {}
        d      (idiff/diff-cards before after)]
    (is (= '("a") (:removed d)))))

(deftest diff-retier-test
  (let [before {"a" (h/a-card {:id "a" :tier :rule})}
        after  {"a" (h/a-card {:id "a" :tier :principle})}
        d      (idiff/diff-cards before after)]
    (is (= '("a") (:retiered d)))
    (is (empty? (:rewritten d)))))

(deftest diff-rewrite-test
  (let [before {"a" (h/a-card {:id "a" :tier :rule :text "old"})}
        after  {"a" (h/a-card {:id "a" :tier :rule :text "new"})}
        d      (idiff/diff-cards before after)]
    (is (empty? (:retiered d)))
    (is (= 1 (count (:rewritten d))))
    (is (= "a" (:card-id (first (:rewritten d)))))
    (is (= "old" (:from (first (:rewritten d)))))
    (is (= "new" (:to (first (:rewritten d)))))))
