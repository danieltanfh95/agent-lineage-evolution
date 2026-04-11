(ns succession.identity.cli.replay-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [cheshire.core :as json]
            [succession.identity.cli.replay :as replay]
            [succession.identity.store.observations :as store-obs]
            [succession.identity.store.test-helpers :as h]))

(def ^:dynamic *root* nil)

(defn with-tmp-root [t]
  (let [root (h/tmp-dir! "succession-cli-replay")]
    (binding [*root* root]
      (try (t)
           (finally (h/delete-tree! root))))))

(use-fixtures :each with-tmp-root)

(defn- write-transcript!
  "Write a minimal JSONL transcript with `tool-uses` entries.
   Each tool-use becomes a `tool_use` type entry."
  [root tool-uses session-id]
  (let [path (str root "/transcript.jsonl")]
    (with-open [w (io/writer path)]
      (.write w (str (json/generate-string
                       {:type "human"
                        :session_id session-id
                        :message {:content "hi"}
                        :timestamp "2026-04-11T12:00:00Z"})
                     "\n"))
      (doseq [[i tu] (map-indexed vector tool-uses)]
        (.write w (str (json/generate-string
                         {:type "tool_use"
                          :session_id session-id
                          :tool_name (:tool-name tu)
                          :tool_input (:tool-input tu)
                          :timestamp (format "2026-04-11T12:00:%02dZ" (+ 10 i))})
                       "\n"))))
    path))

(deftest replay-empty-transcript-test
  (let [path (str *root* "/empty.jsonl")
        _    (spit path "")
        result (replay/run *root* path)]
    (is (= 0 (:tool-uses result)))
    (is (= 0 (:observations-written result)))
    (is (.exists (io/file *root* ".succession-next/.succession")))))

(deftest replay-walks-tool-uses-test
  (let [path (write-transcript!
               *root*
               [{:tool-name "Bash"  :tool-input {:command "ls"}}
                {:tool-name "Edit"  :tool-input {:file_path "foo.clj"}}
                {:tool-name "Bash"  :tool-input {:command "pwd"}}]
               "s1")
        result (replay/run *root* path)]
    (is (= 3 (:tool-uses result)))
    (is (= 3 (:observations-written result)))
    (is (= 1 (:sessions result)))
    (is (= {"Bash" 2 "Edit" 1} (:tool-distribution result)))))

(deftest replay-sandbox-is-isolated-test
  (testing "replay writes to .succession-next/, never to .succession/"
    (let [path (write-transcript!
                 *root* [{:tool-name "Edit" :tool-input {:a 1}}]
                 "s1")
          _    (replay/run *root* path)]
      (is (.exists (io/file *root* ".succession-next/.succession/observations")))
      (is (not (.exists (io/file *root* ".succession/observations")))))))

(deftest replay-observations-survive-round-trip-test
  (let [path (write-transcript!
               *root*
               [{:tool-name "Bash" :tool-input {:command "echo hi"}}]
               "s1")
        _    (replay/run *root* path)
        loaded (store-obs/load-all-observations
                 (str *root* "/.succession-next"))]
    (is (= 1 (count loaded)))
    (is (= :confirmed (:observation/kind (first loaded))))
    (is (str/starts-with? (:observation/card-id (first loaded))
                           "replay-placeholder-bash"))))

(deftest replay-resets-sandbox-each-run-test
  (let [path (write-transcript!
               *root* [{:tool-name "Bash" :tool-input {:command "a"}}]
               "s1")]
    (replay/run *root* path)
    (replay/run *root* path)
    ;; After second run, we still have exactly 1 observation (not 2)
    (let [loaded (store-obs/load-all-observations
                   (str *root* "/.succession-next"))]
      (is (= 1 (count loaded))))))

(deftest replay-missing-transcript-throws-test
  (is (thrown? Exception
               (replay/run *root* (str *root* "/no-such-file.jsonl")))))
