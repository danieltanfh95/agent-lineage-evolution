(ns succession.skill-test
  (:require [clojure.test :refer [deftest is testing]]
            [succession.skill :as skill]
            [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]))

(deftest build-skill-extraction-prompt-test
  (testing "includes transcript and expected schema"
    (let [prompt (skill/build-skill-extraction-prompt "USER: help me debug\nASSISTANT: ok")]
      (is (str/includes? prompt "help me debug"))
      (is (str/includes? prompt "skill_name"))
      (is (str/includes? prompt "context"))
      (is (str/includes? prompt "trigger"))
      (is (str/includes? prompt "file_patterns"))
      (is (str/includes? prompt "steps"))
      (is (str/includes? prompt "knowledge"))
      (is (str/includes? prompt "rules")))))

(deftest parse-skill-result-test
  (testing "parses valid JSON"
    (let [result (skill/parse-skill-result
                  (json/generate-string {:skill_name "test-skill"
                                          :description "a test"
                                          :context {:trigger "always"}
                                          :steps ["step 1"]
                                          :knowledge ["fact 1"]
                                          :rules []}))]
      (is (some? result))
      (is (= "test-skill" (:skill_name result)))))

  (testing "strips markdown fencing"
    (let [result (skill/parse-skill-result
                  (str "```json\n"
                       (json/generate-string {:skill_name "test" :description "t"})
                       "\n```"))]
      (is (some? result))))

  (testing "returns nil for invalid JSON"
    (is (nil? (skill/parse-skill-result "not json"))))

  (testing "returns nil for JSON without skill_name"
    (is (nil? (skill/parse-skill-result "{\"other\": 1}"))))

  (testing "returns nil for nil input"
    (is (nil? (skill/parse-skill-result nil)))))

(deftest generate-skill-md-test
  (let [result {:description "Debug Express APIs"
                :context {:trigger "when debugging Express API endpoints"
                          :file_patterns ["routes/*.ts" "middleware/*.ts"]
                          :keywords ["express" "api" "debug"]}
                :steps ["Check the route handler" "Add logging" "Test with curl"]
                :knowledge ["Express uses middleware chain" "Error handler must be 4-arg"]
                :rules [{:rule "Always check error middleware" :enforcement "advisory"}
                        {:rule "Never delete routes" :enforcement "mechanical"}]}
        md (skill/generate-skill-md result "express-debug")]

    (testing "has frontmatter"
      (is (str/starts-with? md "---"))
      (is (str/includes? md "name: express-debug")))

    (testing "has When to Use section"
      (is (str/includes? md "## When to Use"))
      (is (str/includes? md "debugging Express API endpoints"))
      (is (str/includes? md "routes/*.ts")))

    (testing "has Workflow section with numbered steps"
      (is (str/includes? md "## Workflow"))
      (is (str/includes? md "1. Check the route handler"))
      (is (str/includes? md "2. Add logging"))
      (is (str/includes? md "3. Test with curl")))

    (testing "has Knowledge section"
      (is (str/includes? md "## Knowledge"))
      (is (str/includes? md "- Express uses middleware chain")))

    (testing "has Rules section"
      (is (str/includes? md "## Rules"))
      (is (str/includes? md "[advisory] Always check error middleware"))
      (is (str/includes? md "[mechanical] Never delete routes")))))

(deftest write-skill-test
  (let [tmp-dir (str (fs/create-temp-dir {:prefix "skill-write-"}))]
    (try
      (let [result {:skill_name "test-skill"
                    :description "A test skill"
                    :context {:trigger "always" :file_patterns [] :keywords []}
                    :steps ["do it"]
                    :knowledge ["stuff"]
                    :rules []}
            path (skill/write-skill! result "test-skill" "project" tmp-dir)]

        (testing "creates SKILL.md"
          (is (fs/exists? path))
          (is (str/ends-with? path "SKILL.md")))

        (testing "writes to correct directory"
          (is (str/includes? path ".succession/skills/test-skill")))

        (testing "content is valid"
          (let [content (slurp path)]
            (is (str/includes? content "test-skill"))
            (is (str/includes? content "A test skill")))))

      (finally
        (fs/delete-tree tmp-dir)))))

(deftest parse-args-test
  (testing "parses all options"
    (let [opts (skill/parse-args ["--name" "my-skill" "--apply" "--scope" "global" "--last"])]
      (is (= "my-skill" (:skill-name opts)))
      (is (:apply opts))
      (is (= "global" (:scope opts)))
      (is (:use-last opts))))

  (testing "parses transcript path"
    (let [opts (skill/parse-args ["/path/to/file.jsonl"])]
      (is (= "/path/to/file.jsonl" (:transcript-path opts)))))

  (testing "defaults"
    (let [opts (skill/parse-args [])]
      (is (= "project" (:scope opts)))
      (is (= 0 (:from-turn opts)))
      (is (false? (:interactive opts)))
      (is (false? (:apply opts))))))
