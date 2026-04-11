(ns succession.identity.cli.consult-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [succession.identity.config :as config]
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

(deftest cards-to-scored-reads-real-weights-test
  (testing "cards-to-scored produces non-zero weight for a card with observations"
    (let [heavy (h/a-card {:id "heavy" :tier :rule})
          light (h/a-card {:id "light" :tier :rule})
          _     (store-cards/write-card! *root* heavy)
          _     (store-cards/write-card! *root* light)
          _     (store-cards/materialize-promoted! *root*)
          ;; Seed 'heavy' with three observations across three sessions and
          ;; a real time span; 'light' gets only one observation same-session.
          _     (doseq [[sess ts]
                        [["hs1" #inst "2026-01-05T10:00:00Z"]
                         ["hs2" #inst "2026-02-10T10:00:00Z"]
                         ["hs3" #inst "2026-03-20T10:00:00Z"]]]
                  (store-obs/write-observation! *root*
                    (h/an-observation {:id (str "obs-heavy-" sess)
                                       :at ts :session sess
                                       :card-id "heavy"})))
          _     (store-obs/write-observation! *root*
                  (h/an-observation {:id "obs-light-1"
                                     :at #inst "2026-03-20T10:00:00Z"
                                     :session "ls1"
                                     :card-id "light"}))
          now   #inst "2026-04-01T00:00:00Z"
          scored (consult-cli/cards-to-scored
                   *root* [heavy light] config/default-config now)
          by-id  (into {} (map (juxt (comp :card/id :card) identity)) scored)]
      (is (pos? (:weight (get by-id "heavy"))))
      (is (pos? (:weight (get by-id "light"))))
      (is (> (:weight (get by-id "heavy"))
             (:weight (get by-id "light")))
          "heavy card (3 sessions, span) should outweigh single-observation light card"))))
