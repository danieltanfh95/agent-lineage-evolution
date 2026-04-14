(ns succession.store.cards-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [succession.store.cards :as cards]
            [succession.store.test-helpers :as h]))

(def ^:dynamic *root* nil)

(defn with-tmp-root [t]
  (let [root (h/tmp-dir! "succession-store-cards")]
    (binding [*root* root]
      (try (t)
           (finally (h/delete-tree! root))))))

(use-fixtures :each with-tmp-root)

(deftest round-trip-minimal-card-test
  (testing "a card written to disk and read back is equal to the original"
    (let [c    (h/a-card {:id "r1" :tier :rule
                           :text "first line of body\nsecond line"})
          path (cards/write-card! *root* c)
          back (cards/read-card path)]
      (is (= (:card/id c)        (:card/id back)))
      (is (= (:card/tier c)      (:card/tier back)))
      (is (= (:card/category c)  (:card/category back)))
      (is (= (:card/text c)      (:card/text back)))
      (is (= (:card/provenance c)
             (dissoc (:card/provenance back) nil))))))

(deftest round-trip-rich-card-test
  (testing "tags and fingerprint survive round-trip"
    (let [c    (h/a-card {:id "r2" :tier :principle
                           :category :failure-inheritance
                           :tags [:git :safety]
                           :fingerprint "tool=Bash,cmd=git push --force"})
          _    (cards/write-card! *root* c)
          back (cards/read-card (:card/file
                                  (first (cards/load-all-cards *root*))))]
      (is (= [:git :safety]       (:card/tags back)))
      (is (= "tool=Bash,cmd=git push --force"
             (:card/fingerprint back)))
      (is (= :principle (:card/tier back)))
      (is (= :failure-inheritance (:card/category back))))))

(deftest load-all-cards-multi-tier-test
  (testing "load-all-cards walks every tier directory"
    (cards/write-card! *root* (h/a-card {:id "p1" :tier :principle}))
    (cards/write-card! *root* (h/a-card {:id "r1" :tier :rule}))
    (cards/write-card! *root* (h/a-card {:id "e1" :tier :ethic}))
    (let [loaded (cards/load-all-cards *root*)
          ids    (set (map :card/id loaded))]
      (is (= 3 (count loaded)))
      (is (= #{"p1" "r1" "e1"} ids)))))

(deftest empty-tree-returns-empty-test
  (testing "loading from an empty root returns [] not an exception"
    (is (= [] (cards/load-all-cards *root*)))
    (is (nil? (cards/read-promoted-snapshot *root*)))))

(deftest materialize-promoted-snapshot-test
  (testing "materialize writes promoted.edn with every card"
    (cards/write-card! *root* (h/a-card {:id "p1" :tier :principle}))
    (cards/write-card! *root* (h/a-card {:id "r1" :tier :rule}))
    (let [_     (cards/materialize-promoted! *root*)
          snap  (cards/read-promoted-snapshot *root*)
          ids   (set (map :card/id (:cards snap)))]
      (is (some? snap))
      (is (= #{"p1" "r1"} ids))
      (is (inst? (:at snap))))))

(deftest write-is-idempotent-per-id-test
  (testing "writing the same id twice overwrites, doesn't duplicate"
    (cards/write-card! *root* (h/a-card {:id "same" :tier :rule :text "v1"}))
    (cards/write-card! *root* (h/a-card {:id "same" :tier :rule :text "v2"}))
    (let [loaded (cards/load-all-cards *root*)]
      (is (= 1 (count loaded)))
      (is (= "v2" (:card/text (first loaded)))))))
