(ns succession.extract-test
  (:require [clojure.test :refer [deftest is testing]]
            [succession.extract :as extract]
            [succession.yaml :as yaml]
            [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]))

(deftest build-cli-extraction-prompt-test
  (testing "includes all expected sections"
    (let [prompt (extract/build-cli-extraction-prompt
                  "USER: don't use subagents\nASSISTANT: ok"
                  ["- no-force-push: Never force-push"])]
      (is (str/includes? prompt "don't use subagents"))
      (is (str/includes? prompt "no-force-push"))
      ;; CLI prompt has degradation_points (unlike stop hook prompt)
      (is (str/includes? prompt "degradation_points"))
      (is (str/includes? prompt "approximate_turn"))
      ;; CLI prompt has summary field
      (is (str/includes? prompt "\"summary\""))
      ;; All enforcement tiers
      (is (str/includes? prompt "mechanical"))
      (is (str/includes? prompt "semantic"))
      (is (str/includes? prompt "advisory"))
      ;; All categories
      (is (str/includes? prompt "strategy"))
      (is (str/includes? prompt "failure-inheritance"))
      (is (str/includes? prompt "relational-calibration"))
      (is (str/includes? prompt "meta-cognition")))))

(deftest parse-extraction-result-test
  (testing "parses valid JSON"
    (let [result (extract/parse-extraction-result
                  (json/generate-string {:rules [{:id "test" :enforcement "advisory"}]
                                          :summary "good session"}))]
      (is (some? result))
      (is (= 1 (count (:rules result))))))

  (testing "strips markdown fencing"
    (let [result (extract/parse-extraction-result
                  (str "```json\n"
                       (json/generate-string {:rules [] :summary "ok"})
                       "\n```"))]
      (is (some? result))
      (is (= [] (:rules result)))))

  (testing "returns nil for invalid JSON"
    (is (nil? (extract/parse-extraction-result "not json at all"))))

  (testing "returns nil for JSON without rules key"
    (is (nil? (extract/parse-extraction-result "{\"other\": 1}"))))

  (testing "returns nil for nil input"
    (is (nil? (extract/parse-extraction-result nil)))))

(deftest write-rules-test
  (let [tmp-dir (str (fs/create-temp-dir {:prefix "extract-write-"}))
        cwd tmp-dir]
    (try
      (fs/create-dirs (str cwd "/.succession/rules"))

      (let [rules [{:id "test-rule-1"
                     :enforcement "advisory"
                     :category "strategy"
                     :type "correction"
                     :scope "project"
                     :summary "Don't do the thing"
                     :evidence "User said no"}
                    {:id "test-rule-2"
                     :enforcement "mechanical"
                     :category "failure-inheritance"
                     :type "correction"
                     :scope "project"
                     :summary "Never force push"
                     :evidence "User was angry"
                     :enforcement_directives ["block_bash_pattern: git push.*(--force|-f)"
                                               "reason: Force-push blocked"]}]
            written (extract/write-rules! rules cwd "test-session")]

        (testing "writes correct number of rules"
          (is (= 2 written)))

        (testing "creates rule files"
          (is (fs/exists? (str cwd "/.succession/rules/test-rule-1.md")))
          (is (fs/exists? (str cwd "/.succession/rules/test-rule-2.md"))))

        (testing "rule file has correct frontmatter"
          (let [rule (yaml/parse-rule-file (str cwd "/.succession/rules/test-rule-1.md"))]
            (is (= "test-rule-1" (:id rule)))
            (is (= "advisory" (:enforcement rule)))
            (is (= "strategy" (:category rule)))
            (is (= "test-session" (get-in rule [:source :session])))))

        (testing "mechanical rule has enforcement directives in body"
          (let [rule (yaml/parse-rule-file (str cwd "/.succession/rules/test-rule-2.md"))]
            (is (str/includes? (:body rule) "block_bash_pattern"))))

        (testing "skips existing rules"
          (let [written-again (extract/write-rules! rules cwd "test-session")]
            (is (= 0 written-again)))))

      (finally
        (fs/delete-tree tmp-dir)))))

(deftest parse-args-test
  (testing "parses all options"
    (let [opts (extract/parse-args ["--last" "--from-turn" "5" "--apply"])]
      (is (:use-last opts))
      (is (= 5 (:from-turn opts)))
      (is (:apply opts))))

  (testing "parses transcript path"
    (let [opts (extract/parse-args ["/path/to/transcript.jsonl"])]
      (is (= "/path/to/transcript.jsonl" (:transcript-path opts)))))

  (testing "parses session"
    (let [opts (extract/parse-args ["--session" "abc-123"])]
      (is (= "abc-123" (:session-id opts)))))

  (testing "defaults"
    (let [opts (extract/parse-args [])]
      (is (= 0 (:from-turn opts)))
      (is (false? (:interactive opts)))
      (is (false? (:apply opts))))))
