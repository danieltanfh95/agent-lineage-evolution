(ns succession.yaml-edge-test
  (:require [clojure.test :refer [deftest is testing]]
            [succession.yaml :as yaml]
            [babashka.fs :as fs]
            [clojure.string :as str]))

(deftest frontmatter-with-dashes-in-body-test
  (let [tmp-dir (str (fs/create-temp-dir {:prefix "yaml-edge-"}))
        rule-file (str tmp-dir "/dashes-in-body.md")]
    (try
      ;; Body contains --- which should NOT be confused with frontmatter delimiter
      (spit rule-file
            (str "---\n"
                 "id: test-rule\n"
                 "scope: project\n"
                 "enforcement: advisory\n"
                 "type: preference\n"
                 "source:\n"
                 "  session: s1\n"
                 "  timestamp: '2026-01-01'\n"
                 "  evidence: 'user said something'\n"
                 "enabled: true\n"
                 "---\n\n"
                 "Don't use horizontal rules.\n\n"
                 "---\n\n"
                 "This is still body content after a markdown divider."))

      (testing "body with --- is preserved correctly"
        (let [rule (yaml/parse-rule-file rule-file)]
          (is (= "test-rule" (:id rule)))
          (is (str/includes? (:body rule) "horizontal rules"))
          (is (str/includes? (:body rule) "still body content"))))

      (finally
        (fs/delete-tree tmp-dir)))))

(deftest malformed-yaml-test
  (let [tmp-dir (str (fs/create-temp-dir {:prefix "yaml-bad-"}))
        no-fm-file (str tmp-dir "/no-frontmatter.md")
        partial-fm-file (str tmp-dir "/partial.md")]
    (try
      (spit no-fm-file "Just a plain file with no frontmatter.")
      (spit partial-fm-file "---\nid: broken\n")  ;; Missing closing ---

      (testing "returns nil for file without frontmatter"
        (is (nil? (yaml/parse-rule-file no-fm-file))))

      (testing "returns nil for file with unclosed frontmatter"
        (is (nil? (yaml/parse-rule-file partial-fm-file))))

      (finally
        (fs/delete-tree tmp-dir)))))

(deftest empty-effectiveness-defaults-test
  (let [tmp-dir (str (fs/create-temp-dir {:prefix "yaml-eff-"}))
        rule-file (str tmp-dir "/old-style.md")]
    (try
      ;; Rule without effectiveness block (pre-Phase 2 format)
      (spit rule-file
            (str "---\n"
                 "id: old-rule\n"
                 "scope: global\n"
                 "enforcement: advisory\n"
                 "type: correction\n"
                 "source:\n"
                 "  session: abc\n"
                 "  timestamp: '2026-01-01'\n"
                 "  evidence: 'test'\n"
                 "enabled: true\n"
                 "---\n\n"
                 "Some old rule body."))

      (testing "defaults effectiveness to zeros"
        (let [rule (yaml/parse-rule-file rule-file)]
          (is (= 0 (get-in rule [:effectiveness :times-followed])))
          (is (= 0 (get-in rule [:effectiveness :times-violated])))
          (is (= 0 (get-in rule [:effectiveness :times-overridden])))
          (is (nil? (get-in rule [:effectiveness :last-evaluated])))))

      (testing "defaults category to strategy"
        (let [rule (yaml/parse-rule-file rule-file)]
          (is (= "strategy" (:category rule)))))

      (finally
        (fs/delete-tree tmp-dir)))))

(deftest parse-directives-reason-test
  (testing "reason defaults to generic message when missing"
    (let [body "## Enforcement\n- block_tool: Agent"
          directives (yaml/parse-directives body)]
      (is (= 1 (count directives)))
      (is (= "Blocked by rule" (:reason (first directives))))))

  (testing "explicit reason is used"
    (let [body "## Enforcement\n- block_tool: Agent\n- reason: No agents allowed"
          directives (yaml/parse-directives body)]
      (is (= "No agents allowed" (:reason (first directives)))))))

(deftest write-and-reparse-roundtrip-test
  (let [tmp-dir (str (fs/create-temp-dir {:prefix "yaml-rt-"}))
        rule-file (str tmp-dir "/roundtrip.md")]
    (try
      (let [original {:id "roundtrip-test"
                      :scope "project"
                      :enforcement "mechanical"
                      :category "failure-inheritance"
                      :type "correction"
                      :source {:session "s1"
                               :timestamp "2026-04-01T10:00:00Z"
                               :evidence "User said: don't do that"}
                      :overrides ["old-rule"]
                      :enabled true
                      :effectiveness {:times-followed 5
                                      :times-violated 2
                                      :times-overridden 0
                                      :last-evaluated "2026-04-01T12:00:00Z"}
                      :body "Never do the thing.\n\n## Enforcement\n- block_bash_pattern: dangerous.*command\n- reason: Too dangerous"}]
        (yaml/write-rule-file rule-file original)
        (let [reparsed (yaml/parse-rule-file rule-file)]
          (testing "id round-trips"
            (is (= "roundtrip-test" (:id reparsed))))
          (testing "category round-trips"
            (is (= "failure-inheritance" (:category reparsed))))
          (testing "effectiveness round-trips"
            (is (= 5 (get-in reparsed [:effectiveness :times-followed])))
            (is (= 2 (get-in reparsed [:effectiveness :times-violated]))))
          (testing "body round-trips"
            (is (str/includes? (:body reparsed) "Never do the thing"))
            (is (str/includes? (:body reparsed) "block_bash_pattern")))
          (testing "overrides round-trips"
            (is (= ["old-rule"] (:overrides reparsed))))))

      (finally
        (fs/delete-tree tmp-dir)))))
