(ns succession.transcript-test
  (:require [clojure.test :refer [deftest is testing]]
            [succession.transcript :as transcript]
            [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]))

(defn- make-transcript [tmp-dir entries]
  (let [f (str tmp-dir "/transcript.jsonl")]
    (spit f (str/join "\n" (map json/generate-string entries)))
    f))

(deftest read-transcript-text-test
  (let [tmp-dir (str (fs/create-temp-dir {:prefix "transcript-test-"}))]
    (try
      (let [transcript-file (make-transcript tmp-dir
                              [{:type "human" :message {:content "hello"}}
                               {:type "assistant" :message {:content "hi there"}}
                               {:type "tool_use" :tool_name "Read"}
                               {:type "human" :message {:content "fix the bug"}}
                               {:type "assistant" :message {:content "done"}}])]

        (testing "formats user and assistant messages"
          (let [text (transcript/read-transcript-text transcript-file)]
            (is (str/includes? text "USER: hello"))
            (is (str/includes? text "ASSISTANT: hi there"))
            (is (str/includes? text "USER: fix the bug"))
            (is (str/includes? text "ASSISTANT: done"))
            ;; tool_use excluded
            (is (not (str/includes? text "Read")))))

        (testing "from-turn skips early messages"
          (let [text (transcript/read-transcript-text transcript-file :from-turn 2)]
            (is (not (str/includes? text "hello")))
            (is (not (str/includes? text "hi there")))
            (is (str/includes? text "fix the bug"))))

        (testing "cap-bytes limits output"
          (let [text (transcript/read-transcript-text transcript-file :cap-bytes 20)]
            (is (<= (count text) 20))))

        (testing "handles array content format"
          (let [f (make-transcript tmp-dir
                    [{:type "human" :message {:content [{:type "text" :text "part1"}
                                                         {:type "text" :text "part2"}]}}])
                text (transcript/read-transcript-text f)]
            (is (str/includes? text "USER: part1 part2")))))

      (testing "returns nil for missing file"
        (is (nil? (transcript/read-transcript-text "/nonexistent"))))

      (testing "returns nil for nil path"
        (is (nil? (transcript/read-transcript-text nil))))

      (finally
        (fs/delete-tree tmp-dir)))))

(deftest count-turns-test
  (let [tmp-dir (str (fs/create-temp-dir {:prefix "transcript-count-"}))]
    (try
      (let [f (make-transcript tmp-dir
                [{:type "human" :message {:content "hello"}}
                 {:type "assistant" :message {:content "hi"}}
                 {:type "tool_use" :tool_name "Read"}
                 {:type "human" :message {:content "bye"}}])]

        (testing "counts human + assistant only"
          (is (= 3 (transcript/count-turns f)))))

      (testing "returns nil for missing file"
        (is (nil? (transcript/count-turns "/nonexistent"))))

      (finally
        (fs/delete-tree tmp-dir)))))

(deftest find-latest-transcript-test
  (let [tmp-dir (str (fs/create-temp-dir {:prefix "transcript-find-"}))
        original-home (System/getProperty "user.home")]
    (try
      (System/setProperty "user.home" tmp-dir)

      ;; Create fake project structure
      (let [proj-dir (str tmp-dir "/.claude/projects/proj-hash-1")]
        (fs/create-dirs proj-dir)

        ;; Older transcript (different cwd)
        (let [old-file (str proj-dir "/old-session.jsonl")]
          (spit old-file (json/generate-string {:type "human" :cwd "/other/project"
                                                 :message {:content "old"}}))
          ;; Set older modification time
          (fs/set-last-modified-time old-file
            (java.nio.file.attribute.FileTime/fromMillis 1000)))

        ;; Newer transcript (matching cwd)
        (let [new-file (str proj-dir "/new-session.jsonl")]
          (spit new-file (json/generate-string {:type "human" :cwd "/my/project"
                                                 :message {:content "new"}}))
          (fs/set-last-modified-time new-file
            (java.nio.file.attribute.FileTime/fromMillis 2000)))

        (testing "finds transcript matching cwd"
          (let [result (transcript/find-latest-transcript "/my/project")]
            (is (some? result))
            (is (str/ends-with? result "new-session.jsonl")))))

      (finally
        (System/setProperty "user.home" original-home)
        (fs/delete-tree tmp-dir)))))
