(ns succession.identity.store.observations-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [succession.identity.store.observations :as store-obs]
            [succession.identity.domain.rollup :as rollup]
            [succession.identity.store.test-helpers :as h]))

(def ^:dynamic *root* nil)

(defn with-tmp-root [t]
  (let [root (h/tmp-dir! "succession-store-obs")]
    (binding [*root* root]
      (try (t)
           (finally (h/delete-tree! root))))))

(use-fixtures :each with-tmp-root)

(deftest round-trip-observation-test
  (testing "an observation written to disk reads back equal"
    (let [o (h/an-observation {:id "o1"
                                :at #inst "2026-04-11T12:34:56Z"
                                :session "s1"
                                :card-id "c1"
                                :kind :confirmed})
          path (store-obs/write-observation! *root* o)
          back (store-obs/read-observation path)]
      (is (= o back)))))

(deftest session-scan-returns-all-observations-test
  (testing "load-session-observations returns every file in the session dir"
    (doseq [i (range 10)]
      (store-obs/write-observation!
        *root*
        (h/an-observation {:id      (str "o" i)
                            :at      (java.util.Date/from
                                       (.plusSeconds (java.time.Instant/parse "2026-04-11T12:34:56Z")
                                                     i))
                            :session "s1"
                            :card-id "c1"
                            :kind    :confirmed})))
    (let [loaded (store-obs/load-session-observations *root* "s1")]
      (is (= 10 (count loaded)))
      (is (every? #(= "c1" (:observation/card-id %)) loaded)))))

(deftest feeds-rollup-test
  (testing "store-loaded observations are usable by domain/rollup"
    (doseq [i (range 3)]
      (store-obs/write-observation!
        *root*
        (h/an-observation {:id      (str "o" i)
                            :at      (java.util.Date/from
                                       (.plusSeconds (java.time.Instant/parse "2026-04-11T12:00:00Z")
                                                     i))
                            :session "s1"
                            :card-id "c1"
                            :kind    :confirmed})))
    (let [obs    (store-obs/load-session-observations *root* "s1")
          rolled (rollup/rollup-by-session obs)]
      (is (= 1 (rollup/distinct-sessions rolled)))
      (is (= 3 (rollup/total-confirmed rolled))))))

(deftest empty-session-returns-empty-test
  (is (= [] (store-obs/load-session-observations *root* "no-such-session")))
  (is (= [] (store-obs/load-all-observations *root*))))

(deftest observations-by-card-grouping-test
  (testing "multi-card observations group correctly"
    (store-obs/write-observation!
      *root* (h/an-observation {:id "o1"
                                 :at #inst "2026-04-11T12:00:00Z"
                                 :session "s1" :card-id "a" :kind :confirmed}))
    (store-obs/write-observation!
      *root* (h/an-observation {:id "o2"
                                 :at #inst "2026-04-11T12:01:00Z"
                                 :session "s1" :card-id "b" :kind :confirmed}))
    (let [all    (store-obs/load-session-observations *root* "s1")
          by-id  (store-obs/observations-by-card all)]
      (is (= #{"a" "b"} (set (keys by-id))))
      (is (= 1 (count (get by-id "a"))))
      (is (= 1 (count (get by-id "b")))))))
