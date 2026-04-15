(ns succession.store.util-test
  (:require [clojure.test :refer [deftest is testing]]
            [succession.store.util :as util]))

(def ^:private ts-pattern #"^\d{4}-\d{2}-\d{2}T\d{2}-\d{2}-\d{2}-\d+Z$")

(deftest safe-ts-string-date-test
  (testing "java.util.Date input produces filename-safe ISO string"
    (let [ts (util/safe-ts-string (java.util.Date.))]
      (is (re-matches ts-pattern ts)))))

(deftest safe-ts-string-instant-test
  (testing "java.time.Instant input produces filename-safe ISO string"
    (let [ts (util/safe-ts-string (java.time.Instant/now))]
      (is (re-matches ts-pattern ts)))))

(deftest safe-ts-string-nil-fallback-test
  (testing "nil falls back to Instant/now — result is still a valid timestamp"
    (let [ts (util/safe-ts-string nil)]
      (is (re-matches ts-pattern ts)))))
