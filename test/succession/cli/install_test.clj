(ns succession.cli.install-test
  (:require [clojure.test :refer [deftest is testing]]
            [succession.cli.install :as install]))

(def ^:private src "$CLAUDE_PROJECT_DIR/bb/src")

(deftest build-hook-entries-covers-six-events-test
  (let [entries (install/build-hook-entries src)]
    (is (= #{"SessionStart" "UserPromptSubmit" "PreToolUse"
             "PostToolUse" "Stop" "PreCompact"}
           (set (keys entries))))
    (doseq [[_ v] entries]
      (is (vector? v))
      (is (= 1 (count v)))
      (let [cmd (get-in (first v) [:hooks 0 :command])]
        (is (re-find #"bb -cp .*bb/src. -m succession\.core hook " cmd))))))

(deftest merge-hook-entries-preserves-existing-test
  (testing "non-succession hooks in other events survive"
    (let [existing {:hooks {"SessionStart"
                            [{:matcher "" :hooks [{:type "command"
                                                   :command "my-other-tool.sh"}]}]}}
          merged   (install/merge-hook-entries existing (install/build-hook-entries src))
          ss       (get-in merged [:hooks "SessionStart"])]
      (is (some (fn [e] (some #(= "my-other-tool.sh" (:command %)) (:hooks e))) ss))
      (is (some (fn [e] (some #(re-find #"succession\.core" (:command %))
                              (:hooks e))) ss)))))

(deftest merge-hook-entries-idempotent-test
  (testing "running merge twice does not duplicate the succession hooks"
    (let [once  (install/merge-hook-entries {} (install/build-hook-entries src))
          twice (install/merge-hook-entries once (install/build-hook-entries src))]
      (is (= once twice)))))
