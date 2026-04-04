(ns succession.stop-test
  (:require [clojure.test :refer [deftest is testing]]
            [succession.hooks.stop :as stop]
            [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]))

(deftest tier1-keyword-match-test
  (testing "matches correction keywords"
    (is (stop/tier1-keyword-match? ["no, that's wrong"]))
    (is (stop/tier1-keyword-match? ["don't do that"]))
    (is (stop/tier1-keyword-match? ["stop using subagents"]))
    (is (stop/tier1-keyword-match? ["use edit instead"]))
    (is (stop/tier1-keyword-match? ["actually, I want something else"]))
    (is (stop/tier1-keyword-match? ["please undo that change"]))
    (is (stop/tier1-keyword-match? ["revert the last edit"])))

  (testing "does not match normal messages"
    (is (not (stop/tier1-keyword-match? ["looks good, thanks"])))
    (is (not (stop/tier1-keyword-match? ["can you add a test?"])))
    (is (not (stop/tier1-keyword-match? [""])))
    (is (not (stop/tier1-keyword-match? [])))))

(deftest read-recent-user-messages-test
  (let [tmp-dir (str (fs/create-temp-dir {:prefix "stop-test-"}))
        transcript-file (str tmp-dir "/transcript.jsonl")]
    (try
      ;; Write test transcript
      (spit transcript-file
            (str/join "\n"
                      [(json/generate-string {:type "human" :message {:content "first message"}})
                       (json/generate-string {:type "assistant" :message {:content "response 1"}})
                       (json/generate-string {:type "human" :message {:content "second message"}})
                       (json/generate-string {:type "assistant" :message {:content "response 2"}})
                       (json/generate-string {:type "human" :message {:content "don't do that"}})]))

      (testing "returns last 3 human messages"
        (let [msgs (stop/read-recent-user-messages transcript-file)]
          (is (= 3 (count msgs)))
          (is (= "first message" (first msgs)))
          (is (= "don't do that" (last msgs)))))

      (testing "handles array content format"
        (spit transcript-file
              (json/generate-string {:type "human"
                                     :message {:content [{:type "text" :text "hello"}
                                                          {:type "text" :text "world"}]}}))
        (let [msgs (stop/read-recent-user-messages transcript-file)]
          (is (= ["hello world"] msgs))))

      (testing "returns nil for missing file"
        (is (nil? (stop/read-recent-user-messages "/nonexistent/file.jsonl"))))

      (testing "returns nil for nil path"
        (is (nil? (stop/read-recent-user-messages nil))))

      (finally
        (fs/delete-tree tmp-dir)))))

(deftest extract-transcript-window-test
  (let [tmp-dir (str (fs/create-temp-dir {:prefix "stop-extract-"}))
        transcript-file (str tmp-dir "/transcript.jsonl")]
    (try
      (spit transcript-file
            (str/join "\n"
                      [(json/generate-string {:type "human" :message {:content "hello agent"}})
                       (json/generate-string {:type "assistant" :message {:content "hi there"}})
                       (json/generate-string {:type "tool_use" :tool_name "Read"})
                       (json/generate-string {:type "human" :message {:content "please fix the bug"}})]))

      (testing "extracts user and assistant messages only"
        (let [window (stop/extract-transcript-window transcript-file 0 500000)]
          (is (str/includes? window "USER: hello agent"))
          (is (str/includes? window "ASSISTANT: hi there"))
          (is (str/includes? window "USER: please fix the bug"))
          ;; tool_use should be excluded
          (is (not (str/includes? window "Read")))))

      (testing "respects start offset"
        (let [content (slurp transcript-file)
              ;; Offset past the first line
              offset (inc (str/index-of content "\n"))
              window (stop/extract-transcript-window transcript-file offset 500000)]
          (is (not (str/includes? window "hello agent")))
          (is (str/includes? window "hi there"))))

      (testing "returns empty for blank transcript"
        (spit transcript-file "")
        (is (str/blank? (stop/extract-transcript-window transcript-file 0 500000))))

      (finally
        (fs/delete-tree tmp-dir)))))

(deftest load-existing-rule-summaries-test
  (let [tmp-dir (str (fs/create-temp-dir {:prefix "stop-rules-"}))
        rules-dir (str tmp-dir "/rules")]
    (try
      (fs/create-dirs rules-dir)
      (spit (str rules-dir "/no-force-push.md")
            "---\nid: no-force-push\nscope: project\nenforcement: mechanical\ntype: correction\nsource:\n  session: test\n  timestamp: ''\n  evidence: ''\nenabled: true\n---\n\nNever force-push without confirmation.")

      (testing "loads rule summaries"
        (let [summaries (stop/load-existing-rule-summaries rules-dir)]
          (is (= 1 (count summaries)))
          (is (str/includes? (first summaries) "no-force-push"))
          (is (str/includes? (first summaries) "Never force-push"))))

      (testing "handles nonexistent directory"
        (is (empty? (stop/load-existing-rule-summaries "/nonexistent/dir"))))

      (finally
        (fs/delete-tree tmp-dir)))))

(deftest build-extraction-prompt-test
  (testing "includes transcript and existing rules"
    (let [prompt (stop/build-extraction-prompt
                  "USER: don't use subagents\nASSISTANT: ok"
                  ["- no-force-push: Never force-push"])]
      (is (str/includes? prompt "don't use subagents"))
      (is (str/includes? prompt "no-force-push"))
      (is (str/includes? prompt "mechanical"))
      (is (str/includes? prompt "semantic"))
      (is (str/includes? prompt "advisory"))
      (is (str/includes? prompt "strategy"))
      (is (str/includes? prompt "failure-inheritance"))
      (is (str/includes? prompt "JSON")))))
