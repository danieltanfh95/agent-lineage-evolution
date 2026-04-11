(ns succession.identity.cli.config-validate-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [succession.identity.cli.config-validate :as cv]
            [succession.identity.store.test-helpers :as h]))

(def ^:dynamic *root* nil)

(defn with-tmp-root [t]
  (let [root (h/tmp-dir! "succession-cli-config")]
    (binding [*root* root]
      (try (t)
           (finally (h/delete-tree! root))))))

(use-fixtures :each with-tmp-root)

(deftest validate-on-defaults-test
  (testing "validate with no overlay file passes (defaults are valid)"
    (is (= 0 (cv/validate! *root*)))))

(deftest validate-invalid-overlay-test
  (testing "validate flags a broken overlay"
    (io/make-parents (str *root* "/.succession/config.edn"))
    (spit (str *root* "/.succession/config.edn")
          (pr-str {:weight/freq-cap "not-a-number"}))
    (is (= 1 (cv/validate! *root*)))))

(deftest init-writes-starter-test
  (testing "init writes a config file and refuses to overwrite it"
    (is (= 0 (cv/init! *root*)))
    (is (.exists (io/file *root* ".succession/config.edn")))
    (is (= 1 (cv/init! *root*)) "second call refuses to overwrite")))

(deftest init-creates-succession-dir-test
  (testing "init creates the .succession/ root if it doesn't exist"
    (is (not (.exists (io/file *root* ".succession"))))
    (is (= 0 (cv/init! *root*)))
    (is (.exists (io/file *root* ".succession")))))

(deftest init-template-is-valid-test
  (testing "the template written by init is itself valid against the validator"
    (cv/init! *root*)
    ;; The init output should be a valid overlay. validate! should return 0.
    (is (= 0 (cv/validate! *root*)))))
