(ns succession.identity.store.archive-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [succession.identity.store.archive :as archive]
            [succession.identity.store.cards :as cards]
            [succession.identity.store.test-helpers :as h]))

(def ^:dynamic *root* nil)

(defn with-tmp-root [t]
  (let [root (h/tmp-dir! "succession-store-archive")]
    (binding [*root* root]
      (try (t)
           (finally (h/delete-tree! root))))))

(use-fixtures :each with-tmp-root)

(deftest snapshot-copies-promoted-tree-test
  (cards/write-card! *root* (h/a-card {:id "p1" :tier :principle}))
  (cards/write-card! *root* (h/a-card {:id "r1" :tier :rule}))
  (cards/materialize-promoted! *root*)
  (let [dir (archive/snapshot! *root* #inst "2026-04-11T12:00:00Z")]
    (is (.exists (io/file dir "promoted" "principle" "p1.md")))
    (is (.exists (io/file dir "promoted" "rule" "r1.md")))
    (is (.exists (io/file dir "promoted.edn")))))

(deftest list-archives-is-chronological-test
  (cards/write-card! *root* (h/a-card {:id "p1" :tier :principle}))
  (archive/snapshot! *root* #inst "2026-01-01T00:00:00Z")
  (archive/snapshot! *root* #inst "2026-04-11T12:00:00Z")
  (archive/snapshot! *root* #inst "2026-02-15T09:00:00Z")
  (let [archives (archive/list-archives *root*)]
    (is (= 3 (count archives)))
    (is (= (sort archives) archives))))

(deftest empty-tree-snapshot-is-noop-safe-test
  (testing "snapshotting an empty promoted tree does not throw"
    (let [dir (archive/snapshot! *root* #inst "2026-04-11T12:00:00Z")]
      (is (.exists (io/file dir))))))
