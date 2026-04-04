(ns succession.activity-test
  (:require [clojure.test :refer [deftest is testing]]
            [succession.activity :as activity]
            [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]))

(deftest activity-log-path-test
  (testing "returns project-scoped path"
    (is (= "/foo/bar/.succession/log/succession-activity.jsonl"
           (activity/activity-log-path "/foo/bar")))))

(deftest log-activity-event-test
  (let [tmp-dir (str (fs/create-temp-dir {:prefix "activity-test-"}))]
    (try
      (activity/log-activity-event! "session_start" tmp-dir "sess-1"
                                     {:mechanical_rules 5
                                      :skills_loaded "my-skill"})

      (activity/log-activity-event! "correction_tier1" tmp-dir "sess-1"
                                     {:turn 3
                                      :user_msg_snippet "don't do that"})

      (let [log-file (activity/activity-log-path tmp-dir)
            lines (remove str/blank? (str/split-lines (slurp log-file)))]
        (testing "creates log file"
          (is (fs/exists? log-file)))

        (testing "writes two events"
          (is (= 2 (count lines))))

        (testing "first event has correct fields"
          (let [evt (json/parse-string (first lines) true)]
            (is (= "session_start" (:event evt)))
            (is (= "sess-1" (:session evt)))
            (is (= 5 (:mechanical_rules evt)))
            (is (some? (:timestamp evt)))))

        (testing "second event has correct fields"
          (let [evt (json/parse-string (second lines) true)]
            (is (= "correction_tier1" (:event evt)))
            (is (= 3 (:turn evt))))))

      (finally
        (fs/delete-tree tmp-dir)))))

(deftest rotate-log-if-needed-test
  (let [tmp-dir (str (fs/create-temp-dir {:prefix "activity-rot-"}))]
    (try
      (let [log-file (activity/activity-log-path tmp-dir)]
        (fs/create-dirs (fs/parent log-file))

        (testing "no-op when file does not exist"
          (activity/rotate-log-if-needed! tmp-dir)
          (is (not (fs/exists? (str log-file ".1")))))

        (testing "no-op when file is small"
          (spit log-file "small content\n")
          (activity/rotate-log-if-needed! tmp-dir)
          (is (fs/exists? log-file))
          (is (not (fs/exists? (str log-file ".1")))))

        (testing "rotates when file exceeds 1MB"
          ;; Write >1MB of data
          (spit log-file (apply str (repeat 1100000 "x")))
          (activity/rotate-log-if-needed! tmp-dir)
          (is (fs/exists? (str log-file ".1")))
          (is (not (fs/exists? log-file)))))

      (finally
        (fs/delete-tree tmp-dir)))))
