(ns succession.resolve-test
  (:require [clojure.test :refer [deftest is testing]]
            [succession.resolve :as resolve]
            [succession.yaml :as yaml]
            [babashka.fs :as fs]
            [cheshire.core :as json]))

(defn make-rule
  "Create a minimal rule map for testing."
  [overrides]
  (merge {:id "test" :scope "global" :enforcement "mechanical"
          :category "strategy" :type "correction" :enabled true
          :overrides [] :body "" :file "/tmp/test.md"
          :source {:session "test" :timestamp "" :evidence ""}
          :effectiveness {:times-followed 0 :times-violated 0
                          :times-overridden 0 :last-evaluated nil}}
         overrides))

(deftest resolve-rules-test
  (testing "project rules override global with same id"
    (let [global [(make-rule {:id "no-agents" :scope "global" :enforcement "mechanical"
                              :body "## Enforcement\n- block_tool: Agent\n- reason: blocked"})]
          project [(make-rule {:id "no-agents" :scope "project" :enforcement "advisory"
                               :body "Agents allowed here."})]
          resolved (resolve/resolve-rules global project)]
      (is (= 1 (count resolved)))
      (is (= "advisory" (:enforcement (first resolved))))))

  (testing "explicit overrides cancel referenced rules"
    (let [global [(make-rule {:id "strict-mode" :body "Use strict mode."})]
          project [(make-rule {:id "relax-strict" :overrides ["strict-mode"]
                               :enforcement "advisory" :body "Strict not needed."})]
          resolved (resolve/resolve-rules global project)]
      (is (= 1 (count resolved)))
      (is (= "relax-strict" (:id (first resolved))))))

  (testing "disabled rules are filtered out"
    (let [rules [(make-rule {:id "enabled-rule" :enabled true :body "Active."})
                 (make-rule {:id "disabled-rule" :enabled false :body "Inactive."})]
          resolved (resolve/resolve-rules rules [])]
      (is (= 1 (count resolved)))
      (is (= "enabled-rule" (:id (first resolved))))))

  (testing "non-conflicting rules from both scopes are kept"
    (let [global [(make-rule {:id "rule-a" :body "Rule A."})]
          project [(make-rule {:id "rule-b" :enforcement "advisory" :body "Rule B."})]
          resolved (resolve/resolve-rules global project)]
      (is (= 2 (count resolved))))))

(deftest partition-by-tier-test
  (let [rules [(make-rule {:id "m1" :enforcement "mechanical"})
               (make-rule {:id "s1" :enforcement "semantic"})
               (make-rule {:id "a1" :enforcement "advisory"})
               (make-rule {:id "m2" :enforcement "mechanical"})]
        {:keys [mechanical semantic advisory]} (resolve/partition-by-tier rules)]
    (is (= 2 (count mechanical)))
    (is (= 1 (count semantic)))
    (is (= 1 (count advisory)))))

(deftest compile-tool-rules-test
  (let [rules [(make-rule {:id "no-force-push"
                           :body "No force push.\n\n## Enforcement\n- block_bash_pattern: \"git push.*(--force|-f)\"\n- reason: \"Force-push blocked\""})]
        tool-rules (resolve/compile-tool-rules rules)]
    (is (= 1 (count tool-rules)))
    (is (= "git push.*(--force|-f)" (:block_bash_pattern (first tool-rules))))
    (is (= "Force-push blocked" (:reason (first tool-rules))))
    (is (= "no-force-push" (:source (first tool-rules))))))

(deftest review-candidates-test
  (testing "high violation rate flagged for review"
    (let [rules [(make-rule {:id "bad-rule" :enforcement "advisory"
                             :effectiveness {:times-followed 3 :times-violated 8
                                             :times-overridden 0 :last-evaluated nil}})]
          candidates (resolve/review-candidates rules)]
      (is (some #(and (= "bad-rule" (:id %)) (= "review" (:action %))) candidates))))

  (testing "high follow rate advisory flagged for promotion"
    (let [rules [(make-rule {:id "good-rule" :enforcement "advisory"
                             :effectiveness {:times-followed 18 :times-violated 2
                                             :times-overridden 0 :last-evaluated nil}})]
          candidates (resolve/review-candidates rules)]
      (is (some #(and (= "good-rule" (:id %)) (= "promote" (:action %))) candidates))))

  (testing "rules with < 10 evaluations are not flagged"
    (let [rules [(make-rule {:id "new-rule" :enforcement "advisory"
                             :effectiveness {:times-followed 5 :times-violated 4
                                             :times-overridden 0 :last-evaluated nil}})]
          candidates (resolve/review-candidates rules)]
      (is (empty? candidates)))))

(deftest resolve-and-compile-integration-test
  (let [tmp-dir (str (fs/create-temp-dir {:prefix "succession-test-"}))]
    (try
      ;; Setup: create global and project rule dirs
      (let [global-rules-dir (str tmp-dir "/fakehome/.succession/rules")
            project-rules-dir (str tmp-dir "/project/.succession/rules")
            compiled-dir (str tmp-dir "/project/.succession/compiled")]
        (fs/create-dirs global-rules-dir)
        (fs/create-dirs project-rules-dir)

        ;; Create a mechanical global rule
        (spit (str global-rules-dir "/no-force-push.md")
              "---\nid: no-force-push\nscope: global\nenforcement: mechanical\ncategory: failure-inheritance\ntype: correction\nsource:\n  session: test\n  timestamp: 2026-01-01T00:00:00Z\n  evidence: test\noverrides: []\nenabled: true\neffectiveness:\n  times_followed: 0\n  times_violated: 0\n  times_overridden: 0\n  last_evaluated: null\n---\n\nNever force-push.\n\n## Enforcement\n- block_bash_pattern: \"git push.*(--force|-f)\"\n- reason: \"Force-push blocked\"\n")

        ;; Create an advisory global rule
        (spit (str global-rules-dir "/be-concise.md")
              "---\nid: be-concise\nscope: global\nenforcement: advisory\ncategory: relational-calibration\ntype: preference\nsource:\n  session: test\n  timestamp: 2026-01-01T00:00:00Z\n  evidence: test\noverrides: []\nenabled: true\n---\n\nKeep responses short and direct.\n")

        ;; Override HOME for this test
        (let [original-home (System/getProperty "user.home")]
          (System/setProperty "user.home" (str tmp-dir "/fakehome"))
          (try
            (let [result (resolve/resolve-and-compile! (str tmp-dir "/project"))]
              (testing "resolve returns counts"
                (is (= 2 (:total result)))
                (is (= 1 (:mechanical result)))
                (is (= 1 (:advisory result))))

              (testing "tool-rules.json created and valid"
                (is (fs/exists? (str compiled-dir "/tool-rules.json")))
                (let [rules (json/parse-string (slurp (str compiled-dir "/tool-rules.json")) true)]
                  (is (= 1 (count rules)))
                  (is (some? (:block_bash_pattern (first rules))))))

              (testing "advisory-summary.md created"
                (is (fs/exists? (str compiled-dir "/advisory-summary.md")))
                (is (clojure.string/includes?
                     (slurp (str compiled-dir "/advisory-summary.md"))
                     "be-concise")))

              (testing "review-candidates.json created"
                (is (fs/exists? (str compiled-dir "/review-candidates.json")))))
            (finally
              (System/setProperty "user.home" original-home)))))
      (finally
        (fs/delete-tree tmp-dir)))))
