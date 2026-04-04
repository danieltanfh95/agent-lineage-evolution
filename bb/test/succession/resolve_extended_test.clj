(ns succession.resolve-extended-test
  (:require [clojure.test :refer [deftest is testing]]
            [succession.resolve :as resolve]
            [clojure.string :as str]))

(deftest compile-semantic-rules-test
  (testing "generates markdown with rule bodies"
    (let [rules [{:id "use-edit-not-sed"
                  :enforcement "semantic"
                  :body "Use the Edit tool instead of sed for modifying source files.\n\n## Enforcement\n- reason: Edit is safer"}
                 {:id "no-subagents-for-simple"
                  :enforcement "semantic"
                  :body "Don't use subagents for simple file lookups."}]
          result (resolve/compile-semantic-rules rules)]
      (is (str/includes? result "# Semantic Rules"))
      (is (str/includes? result "use-edit-not-sed"))
      (is (str/includes? result "Edit tool instead of sed"))
      ;; Enforcement section should be stripped from semantic rules display
      (is (not (str/includes? result "## Enforcement")))
      (is (str/includes? result "no-subagents-for-simple"))))

  (testing "empty rules list"
    (let [result (resolve/compile-semantic-rules [])]
      (is (str/includes? result "# Semantic Rules")))))

(deftest compile-advisory-summary-test
  (testing "includes both advisory and semantic rules"
    (let [advisory [{:id "prefer-concise" :enforcement "advisory" :body "Keep responses short."}]
          semantic [{:id "use-edit" :enforcement "semantic" :body "Use Edit not sed."}]
          result (resolve/compile-advisory-summary advisory semantic)]
      (is (str/includes? result "prefer-concise"))
      (is (str/includes? result "use-edit"))
      (is (str/includes? result "Keep responses short"))
      (is (str/includes? result "Active Rules"))))

  (testing "truncates long bodies to 3 lines"
    (let [advisory [{:id "verbose-rule" :enforcement "advisory"
                     :body "Line 1\nLine 2\nLine 3\nLine 4\nLine 5"}]
          result (resolve/compile-advisory-summary advisory [])]
      ;; Should include first 3 lines joined
      (is (str/includes? result "Line 1"))
      (is (str/includes? result "Line 3"))
      ;; Line 4+ should not appear
      (is (not (str/includes? result "Line 4"))))))

(deftest review-candidates-test
  (testing "flags high violation rate"
    (let [rules [{:id "bad-rule"
                  :enforcement "advisory"
                  :effectiveness {:times-followed 3 :times-violated 8 :times-overridden 0}}]
          candidates (resolve/review-candidates rules)]
      (is (= 1 (count candidates)))
      (is (= "review" (:action (first candidates))))))

  (testing "promotes high-follow advisory"
    (let [rules [{:id "good-advisory"
                  :enforcement "advisory"
                  :effectiveness {:times-followed 9 :times-violated 1 :times-overridden 0}}]
          candidates (resolve/review-candidates rules)]
      (is (some #(= "promote" (:action %)) candidates))))

  (testing "ignores rules with < 10 evaluations"
    (let [rules [{:id "new-rule"
                  :enforcement "advisory"
                  :effectiveness {:times-followed 2 :times-violated 2 :times-overridden 0}}]
          candidates (resolve/review-candidates rules)]
      (is (empty? candidates))))

  (testing "does not promote non-advisory high-follow rules"
    (let [rules [{:id "mechanical-good"
                  :enforcement "mechanical"
                  :effectiveness {:times-followed 9 :times-violated 1 :times-overridden 0}}]
          candidates (resolve/review-candidates rules)]
      ;; mechanical rules shouldn't get promote action (only review if violation is high)
      (is (not (some #(= "promote" (:action %)) candidates))))))
