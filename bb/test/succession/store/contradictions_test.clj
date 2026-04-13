(ns succession.store.contradictions-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [succession.store.contradictions :as store-c]
            [succession.store.test-helpers :as h]))

(def ^:dynamic *root* nil)

(defn with-tmp-root [t]
  (let [root (h/tmp-dir! "succession-store-contradictions")]
    (binding [*root* root]
      (try (t)
           (finally (h/delete-tree! root))))))

(use-fixtures :each with-tmp-root)

(defn- a-contradiction [id category]
  {:succession/entity-type :contradiction
   :contradiction/id id
   :contradiction/at #inst "2026-04-11T12:00:00Z"
   :contradiction/session "s1"
   :contradiction/category category
   :contradiction/between [{:card/id "c1"}]
   :contradiction/detector :pure
   :contradiction/resolution nil
   :contradiction/resolved-at nil
   :contradiction/resolved-by nil
   :contradiction/escalated? false})

(deftest round-trip-test
  (let [c (a-contradiction "cn1" :self-contradictory)
        path (store-c/write-contradiction! *root* c)
        back (store-c/read-contradiction path)]
    (is (= c back))))

(deftest load-all-and-open-test
  (store-c/write-contradiction! *root* (a-contradiction "cn1" :self-contradictory))
  (store-c/write-contradiction! *root*
    (assoc (a-contradiction "cn2" :tier-violation)
           :contradiction/resolved-at #inst "2026-04-11T13:00:00Z"
           :contradiction/resolved-by :pure))
  (is (= 2 (count (store-c/load-all-contradictions *root*))))
  (is (= 1 (count (store-c/open-contradictions *root*))))
  (is (= "cn1" (:contradiction/id (first (store-c/open-contradictions *root*))))))

(deftest session-log-test
  (let [c (a-contradiction "cn1" :self-contradictory)]
    (store-c/write-contradiction! *root* c)
    (store-c/append-to-session-log! *root* "s1" c)
    (store-c/append-to-session-log! *root* "s1" (a-contradiction "cn2" :tier-violation))
    (let [loaded (store-c/load-session-contradictions *root* "s1")]
      (is (= 2 (count loaded)))
      (is (= #{"cn1" "cn2"} (set (map :contradiction/id loaded)))))))

(deftest mark-resolved-test
  (store-c/write-contradiction! *root* (a-contradiction "cn1" :self-contradictory))
  (store-c/mark-resolved! *root* "cn1" :llm #inst "2026-04-11T13:00:00Z")
  (let [[c] (store-c/load-all-contradictions *root*)]
    (is (= :llm (:contradiction/resolved-by c)))
    (is (some? (:contradiction/resolved-at c)))))

(deftest mark-resolved-with-resolution-test
  (store-c/write-contradiction! *root* (a-contradiction "cn1" :self-contradictory))
  (let [resolution {:category :self-contradictory
                    :kind :rewrite
                    :new-text "Revised text"
                    :rationale "Lines were contradictory"
                    :confidence 0.9}]
    (store-c/mark-resolved! *root* "cn1" :llm-reconcile #inst "2026-04-11T13:00:00Z" resolution))
  (let [[c] (store-c/load-all-contradictions *root*)]
    (is (= :llm-reconcile (:contradiction/resolved-by c)))
    (is (= :rewrite (get-in c [:contradiction/resolution :kind])))
    (is (= "Revised text" (get-in c [:contradiction/resolution :new-text])))
    (is (= 0.9 (get-in c [:contradiction/resolution :confidence])))))

(deftest mark-rewrite-applied-test
  (store-c/write-contradiction! *root* (a-contradiction "cn1" :self-contradictory))
  (store-c/mark-resolved! *root* "cn1" :llm-reconcile #inst "2026-04-11T13:00:00Z"
                          {:kind :rewrite :new-text "New text" :confidence 0.9})
  (store-c/mark-rewrite-applied! *root* "cn1" #inst "2026-04-11T14:00:00Z")
  (let [[c] (store-c/load-all-contradictions *root*)]
    (is (some? (get-in c [:contradiction/resolution :applied-at])))))
