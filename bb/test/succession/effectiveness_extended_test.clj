(ns succession.effectiveness-extended-test
  (:require [clojure.test :refer [deftest is testing]]
            [succession.effectiveness :as eff]
            [succession.yaml :as yaml]
            [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]))

(deftest update-effectiveness-counters-test
  (let [tmp-dir (str (fs/create-temp-dir {:prefix "eff-update-"}))
        original-home (System/getProperty "user.home")
        rules-dir (str tmp-dir "/project/rules")]
    (try
      (System/setProperty "user.home" tmp-dir)
      (fs/create-dirs rules-dir)

      ;; Create a rule file
      (let [rule {:id "test-rule"
                  :scope "project"
                  :enforcement "advisory"
                  :category "strategy"
                  :type "correction"
                  :source {:session "s1" :timestamp "" :evidence ""}
                  :overrides []
                  :enabled true
                  :effectiveness {:times-followed 0
                                  :times-violated 0
                                  :times-overridden 0
                                  :last-evaluated nil}
                  :body "Test rule body."}]
        (yaml/write-rule-file (str rules-dir "/test-rule.md") rule))

      ;; Log some events
      (eff/log-event! "rule_followed" {:rule_id "test-rule" :session "s1"})
      (eff/log-event! "rule_followed" {:rule_id "test-rule" :session "s1"})
      (eff/log-event! "rule_violated" {:rule_id "test-rule" :session "s1"})

      ;; Update counters
      (eff/update-effectiveness-counters! rules-dir)

      ;; Verify
      (let [updated (yaml/parse-rule-file (str rules-dir "/test-rule.md"))]
        (testing "followed counter updated"
          (is (= 2 (get-in updated [:effectiveness :times-followed]))))
        (testing "violated counter updated"
          (is (= 1 (get-in updated [:effectiveness :times-violated]))))
        (testing "last-evaluated is set"
          (is (some? (get-in updated [:effectiveness :last-evaluated])))))

      (finally
        (System/setProperty "user.home" original-home)
        (fs/delete-tree tmp-dir)))))

(deftest compute-counters-edge-cases-test
  (testing "empty events list"
    (is (= {} (eff/compute-counters []))))

  (testing "ignores non-follow/violate events"
    (let [events [{:event "rule_created" :rule_id "r1"}
                  {:event "rule_disabled" :rule_id "r1"}
                  {:event "session_summary"}]]
      (is (= {} (eff/compute-counters events)))))

  (testing "multiple rules tracked independently"
    (let [events [{:event "rule_followed" :rule_id "r1"}
                  {:event "rule_followed" :rule_id "r2"}
                  {:event "rule_violated" :rule_id "r1"}
                  {:event "rule_followed" :rule_id "r2"}]
          counters (eff/compute-counters events)]
      (is (= 1 (get-in counters ["r1" :followed])))
      (is (= 1 (get-in counters ["r1" :violated])))
      (is (= 2 (get-in counters ["r2" :followed])))
      (is (= 0 (get-in counters ["r2" :violated]))))))
