(ns succession.judge-test
  (:require [clojure.test :refer [deftest is testing]]
            [succession.judge :as judge]
            [succession.config :as config]
            [succession.hooks.common :as common]
            [cheshire.core :as json]
            [babashka.fs :as fs]))

(deftest parse-verdict-object-test
  (testing "well-formed object verdict parses"
    (let [raw (json/generate-string
               {:rule_id "no-force-push"
                :verdict "violated"
                :retrospective "You ran git push --force."
                :confidence 0.9
                :escalate false})
          v (judge/parse-verdict raw)]
      (is (some? v))
      (is (= "no-force-push" (:rule_id v)))
      (is (= :violated (:verdict v)))
      (is (= 0.9 (:confidence v)))
      (is (false? (:escalate? v))))))

(deftest parse-verdict-followed-test
  (let [raw (json/generate-string {:rule_id "x" :verdict "followed" :confidence 1.0})]
    (is (= :followed (:verdict (judge/parse-verdict raw))))))

(deftest parse-verdict-ambiguous-test
  (let [raw (json/generate-string {:rule_id "x" :verdict "ambiguous" :confidence 0.3})]
    (is (= :ambiguous (:verdict (judge/parse-verdict raw))))))

(deftest parse-verdict-not-applicable-test
  (let [raw (json/generate-string {:rule_id "none" :verdict "not-applicable"})]
    (is (= :not-applicable (:verdict (judge/parse-verdict raw))))))

(deftest parse-verdict-unknown-verdict-test
  (testing "unknown verdict strings fall back to :ambiguous rather than nil"
    (let [raw (json/generate-string {:rule_id "x" :verdict "wonky"})
          v (judge/parse-verdict raw)]
      (is (= :ambiguous (:verdict v))))))

(deftest parse-verdict-malformed-test
  (testing "unparseable input returns nil"
    (is (nil? (judge/parse-verdict "this is not json at all")))
    (is (nil? (judge/parse-verdict nil)))
    (is (nil? (judge/parse-verdict "")))))

(deftest parse-verdict-markdown-fenced-test
  (testing "markdown code fences are stripped"
    (let [raw (str "```json\n"
                   (json/generate-string {:rule_id "r" :verdict "followed"})
                   "\n```")]
      (is (= :followed (:verdict (judge/parse-verdict raw)))))))

(deftest parse-verdict-array-test
  (testing "array form returns the first entry"
    (let [raw (json/generate-string
               [{:rule_id "r1" :verdict "violated" :confidence 0.8}
                {:rule_id "r2" :verdict "followed"}])
          v (judge/parse-verdict raw)]
      (is (= "r1" (:rule_id v))))))

(deftest budget-guard-test
  (testing "budget-exceeded? respects sessionBudgetUsd floor"
    (let [sid (str "budget-test-" (System/nanoTime))
          f (str "/tmp/.succession-judge-budget-" sid)]
      (try
        ;; Start clean
        (fs/delete-if-exists f)
        (is (false? (judge/budget-exceeded? sid {:judge {:sessionBudgetUsd 0.10}})))
        ;; Spend 0.05 — still under
        (judge/add-session-budget! sid 0.05)
        (is (false? (judge/budget-exceeded? sid {:judge {:sessionBudgetUsd 0.10}})))
        ;; Cross the threshold
        (judge/add-session-budget! sid 0.06)
        (is (true? (judge/budget-exceeded? sid {:judge {:sessionBudgetUsd 0.10}})))
        ;; Very low budget — tripped immediately
        (is (true? (judge/budget-exceeded? sid {:judge {:sessionBudgetUsd 0.01}})))
        (finally
          (fs/delete-if-exists f))))))

(deftest config-rejects-haiku-judge-model-test
  (testing "config load refuses haiku as judge.model"
    (let [tmp (fs/create-temp-dir {:prefix "cfg-test-"})
          cfg-file (str tmp "/config.json")]
      (spit cfg-file (json/generate-string {:judge {:model "haiku"}}))
      (is (thrown? clojure.lang.ExceptionInfo
                   (config/load-config cfg-file))))))

(deftest loop-guard-detects-subprocess-env
  (testing "judge-subprocess? respects the env var"
    ;; We can't actually set env vars inside the running bb process; but
    ;; we can verify the function reads from System/getenv so setting
    ;; before launching a subprocess works. Here we simply confirm that
    ;; the default state is not-a-subprocess.
    (is (false? (common/judge-subprocess?)))))
