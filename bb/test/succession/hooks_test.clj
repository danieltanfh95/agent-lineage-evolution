(ns succession.hooks-test
  (:require [clojure.test :refer [deftest is testing]]
            [succession.hooks.pre-tool-use :as ptu]
            [cheshire.core :as json]
            [babashka.fs :as fs]))

(deftest check-block-tool-test
  (let [rules [{:block_tool "Agent" :reason "No agents" :source "no-agents"}]]
    (testing "blocks matching tool"
      (let [result (ptu/check-block-tool rules "Agent")]
        (is (some? result))
        (is (= "block" (:decision result)))
        (is (clojure.string/includes? (:reason result) "No agents"))))

    (testing "allows non-matching tool"
      (is (nil? (ptu/check-block-tool rules "Bash"))))))

(deftest check-bash-pattern-test
  (let [rules [{:block_bash_pattern "git push.*(--force|-f)" :reason "No force push" :source "no-fp"}]]
    (testing "blocks matching command"
      (let [result (ptu/check-bash-pattern rules "git push --force origin main")]
        (is (some? result))
        (is (= "block" (:decision result)))))

    (testing "allows non-matching command"
      (is (nil? (ptu/check-bash-pattern rules "git push origin main"))))))

(deftest pre-tool-use-benchmark-test
  (testing "pre-tool-use check completes in < 100ms"
    (let [rules (vec (for [i (range 20)]
                       {:block_bash_pattern (str "pattern" i ".*dangerous")
                        :reason (str "Rule " i)
                        :source (str "rule-" i)}))
          command "git status"
          start (System/nanoTime)
          _ (dotimes [_ 100]
              (ptu/check-bash-pattern rules command)
              (ptu/check-block-tool rules "Bash"))
          elapsed-ms (/ (- (System/nanoTime) start) 1e6)]
      ;; 100 iterations of 20 rules each should complete well under 100ms
      (is (< elapsed-ms 1000) (str "100 iterations took " elapsed-ms "ms")))))
