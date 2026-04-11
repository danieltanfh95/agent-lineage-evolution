(ns succession.identity.cli.install-test
  (:require [clojure.test :refer [deftest is testing]]
            [succession.identity.cli.install :as install]))

(deftest build-hook-entries-covers-six-events-test
  (let [entries (install/build-hook-entries)]
    (is (= #{"SessionStart" "UserPromptSubmit" "PreToolUse"
             "PostToolUse" "Stop" "PreCompact"}
           (set (keys entries))))
    (doseq [[_ v] entries]
      (is (vector? v))
      (is (= 1 (count v)))
      (let [cmd (get-in (first v) [:hooks 0 :command])]
        (is (re-find #"bb -m succession\.identity\.core hook " cmd))))))

(deftest merge-hook-entries-preserves-existing-test
  (testing "non-succession hooks in other events survive"
    (let [existing {:hooks {"SessionStart"
                            [{:matcher "" :hooks [{:type "command"
                                                   :command "my-other-tool.sh"}]}]}}
          merged   (install/merge-hook-entries existing (install/build-hook-entries))
          ss       (get-in merged [:hooks "SessionStart"])]
      (is (some (fn [e] (some #(= "my-other-tool.sh" (:command %)) (:hooks e))) ss))
      (is (some (fn [e] (some #(re-find #"succession\.identity\.core" (:command %))
                              (:hooks e))) ss)))))

(deftest merge-hook-entries-idempotent-test
  (testing "running merge twice does not duplicate the succession hooks"
    (let [once  (install/merge-hook-entries {} (install/build-hook-entries))
          twice (install/merge-hook-entries once (install/build-hook-entries))]
      (is (= once twice)))))
