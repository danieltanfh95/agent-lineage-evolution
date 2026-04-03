(ns succession.effectiveness-test
  (:require [clojure.test :refer [deftest is testing]]
            [succession.effectiveness :as eff]
            [babashka.fs :as fs]
            [cheshire.core :as json]))

(deftest log-event-test
  (let [tmp-dir (str (fs/create-temp-dir {:prefix "eff-test-"}))
        original-home (System/getProperty "user.home")]
    (try
      (System/setProperty "user.home" tmp-dir)

      (eff/log-event! "rule_created"
                       {:rule_id "test-rule"
                        :category "strategy"
                        :source "extraction"
                        :session "test-session"})

      (eff/log-event! "rule_violated"
                       {:rule_id "test-rule"
                        :context "bad command"
                        :detected_by "pre-tool-use"
                        :session "test-session"})

      (let [log-file (str tmp-dir "/.succession/log/meta-cognition.jsonl")]
        (testing "log file created"
          (is (fs/exists? log-file)))

        (testing "two events logged"
          (let [lines (remove clojure.string/blank? (clojure.string/split-lines (slurp log-file)))]
            (is (= 2 (count lines)))

            (let [first-event (json/parse-string (first lines) true)]
              (is (= "rule_created" (:event first-event)))
              (is (= "test-rule" (:rule_id first-event)))
              (is (= "strategy" (:category first-event))))

            (let [second-event (json/parse-string (second lines) true)]
              (is (= "rule_violated" (:event second-event)))
              (is (= "test-rule" (:rule_id second-event)))))))
      (finally
        (System/setProperty "user.home" original-home)
        (fs/delete-tree tmp-dir)))))

(deftest compute-counters-test
  (testing "groups events by rule_id"
    (let [events [{:event "rule_followed" :rule_id "r1"}
                  {:event "rule_followed" :rule_id "r1"}
                  {:event "rule_violated" :rule_id "r1"}
                  {:event "rule_followed" :rule_id "r2"}
                  {:event "rule_created" :rule_id "r1"}  ;; should be ignored
                  ]
          counters (eff/compute-counters events)]
      (is (= 2 (get-in counters ["r1" :followed])))
      (is (= 1 (get-in counters ["r1" :violated])))
      (is (= 1 (get-in counters ["r2" :followed])))
      (is (= 0 (get-in counters ["r2" :violated] 0))))))
