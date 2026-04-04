(ns succession.pre-tool-use-test
  (:require [clojure.test :refer [deftest is testing]]
            [succession.hooks.pre-tool-use :as ptu]
            [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]))

(deftest check-require-prior-read-test
  (let [tmp-dir (str (fs/create-temp-dir {:prefix "ptu-read-"}))
        transcript-file (str tmp-dir "/transcript.jsonl")
        rules [{:require_prior_read true
                :reason "Must read before editing"
                :source "require-read-before-edit"}]]
    (try
      ;; Transcript with a Read of /foo/bar.clj
      (spit transcript-file
            (str/join "\n"
                      [(json/generate-string {:type "tool_use" :tool_name "Read"
                                              :tool_input {:file_path "/foo/bar.clj"}})
                       (json/generate-string {:type "tool_use" :tool_name "Bash"
                                              :tool_input {:command "ls"}})]))

      (testing "allows edit when file was previously read"
        (is (nil? (ptu/check-require-prior-read rules
                                                 {:file_path "/foo/bar.clj"}
                                                 transcript-file))))

      (testing "blocks edit when file was NOT previously read"
        (let [result (ptu/check-require-prior-read rules
                                                    {:file_path "/foo/other.clj"}
                                                    transcript-file)]
          (is (some? result))
          (is (= "block" (:decision result)))))

      (testing "blocks when no transcript available"
        (let [result (ptu/check-require-prior-read rules
                                                    {:file_path "/foo/bar.clj"}
                                                    nil)]
          (is (some? result))
          (is (= "block" (:decision result)))))

      (testing "no-op when no require_prior_read rules"
        (is (nil? (ptu/check-require-prior-read [] {:file_path "/anything"} transcript-file))))

      (finally
        (fs/delete-tree tmp-dir)))))

(deftest check-block-tool-edge-cases-test
  (let [rules [{:block_tool "Agent" :reason "No agents" :source "no-agents"}
               {:block_tool "WebSearch" :reason "No web search" :source "no-web"}]]
    (testing "blocks first matching rule"
      (let [result (ptu/check-block-tool rules "Agent")]
        (is (= "block" (:decision result)))
        (is (str/includes? (:reason result) "No agents"))))

    (testing "blocks second rule"
      (let [result (ptu/check-block-tool rules "WebSearch")]
        (is (str/includes? (:reason result) "No web search"))))

    (testing "allows unmatched tools"
      (is (nil? (ptu/check-block-tool rules "Read")))
      (is (nil? (ptu/check-block-tool rules "Edit")))
      (is (nil? (ptu/check-block-tool rules "Bash"))))))

(deftest check-bash-pattern-edge-cases-test
  (testing "regex special characters in pattern"
    (let [rules [{:block_bash_pattern "rm\\s+-rf\\s+/"
                  :reason "No rm -rf /"
                  :source "no-rm-rf"}]]
      (is (some? (ptu/check-bash-pattern rules "rm -rf /")))
      (is (some? (ptu/check-bash-pattern rules "sudo rm -rf /tmp")))
      (is (nil? (ptu/check-bash-pattern rules "rm file.txt")))))

  (testing "empty rules list"
    (is (nil? (ptu/check-bash-pattern [] "git push --force")))))
