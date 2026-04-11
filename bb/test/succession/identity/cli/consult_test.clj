(ns succession.identity.cli.consult-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [succession.identity.cli.consult :as consult-cli]
            [succession.identity.store.cards :as store-cards]
            [succession.identity.store.observations :as store-obs]
            [succession.identity.store.test-helpers :as h]))

(def ^:dynamic *root* nil)

(defn with-tmp-root [t]
  (let [root (h/tmp-dir! "succession-cli-consult")]
    (binding [*root* root]
      (try (t)
           (finally (h/delete-tree! root))))))

(use-fixtures :each with-tmp-root)

(deftest parse-args-positional-and-flags-test
  (let [opts (consult-cli/parse-args
               ["about" "to" "force-push"
                "--tier" "principle"
                "--category" "failure-inheritance"
                "--exclude" "a,b"
                "--intent" "ship the fix"])]
    (is (= ["about" "to" "force-push"] (:situation-parts opts)))
    (is (= :principle (:tier opts)))
    (is (= :failure-inheritance (:category opts)))
    (is (= #{"a" "b"} (:exclude opts)))
    (is (= "ship the fix" (:intent opts)))))

(deftest build-framed-prompt-has-required-sections-test
  (let [p (consult-cli/build-framed-prompt "# Consult\n**situation:** hi" "ship")]
    (is (str/includes? p "## Principle"))
    (is (str/includes? p "## Rule"))
    (is (str/includes? p "## Ethic"))
    (is (str/includes? p "## tensions"))
    (is (str/includes? p "## reflection"))
    (is (str/includes? p "ship"))))

(deftest dry-run-does-not-call-llm-test
  (testing "dry-run path returns the consult-view as the reflection"
    ;; Write one card so the candidate pool is non-empty.
    (store-cards/write-card! *root*
      (h/a-card {:id "prefer-edit" :tier :rule}))
    (store-cards/materialize-promoted! *root*)
    (let [result (consult-cli/run *root*
                   ["the" "agent" "wants" "to" "edit" "a" "file"
                    "--dry-run"])]
      (is (:ok? result))
      (is (str/includes? (:reflection result) "# Consult"))
      (is (= 0.0 (:cost-usd result)))))

  (testing "dry-run still logs :consulted observations"
    (let [obs (store-obs/load-all-observations *root*)]
      (is (seq obs))
      (is (every? #(= :consulted (:observation/kind %)) obs))
      (is (every? #(= :consult (:observation/source %)) obs)))))

(deftest empty-identity-graceful-test
  (testing "running consult with no cards still succeeds in dry-run"
    (let [result (consult-cli/run *root*
                   ["hello" "--dry-run"])]
      (is (:ok? result))
      (is (= 0 (:candidate-count result))))))

(deftest blank-situation-throws-test
  (is (thrown? Exception
               (consult-cli/run *root* ["--dry-run"]))))
