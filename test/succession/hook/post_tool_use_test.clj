(ns succession.hook.post-tool-use-test
  "Tests for the pure functions of post-tool-use. The async judge lane
   spawns a subprocess and is not unit-tested here — it's covered by the
   integration shadow-mode run in Phase 2.

   The gate itself moved to `succession.hook.common` so PreToolUse can
   share it; its unit tests live in `hook.common-test`."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [succession.hook.post-tool-use :as ptu]
            [succession.store.test-helpers :as h]))

;; ------------------------------------------------------------------
;; Reminder composition
;; ------------------------------------------------------------------

(deftest build-reminder-renders-cards-test
  (testing "ranked cards appear in the reminder text"
    (let [ranked [{:card (h/a-card {:id "p1" :tier :principle :text "always verify"})
                   :weight 50.0 :recency-fraction 1.0}]
          out    (ptu/build-reminder ranked 3 {})]
      (is (str/includes? out "Identity reminder"))
      (is (str/includes? out "p1")))))

(deftest build-reminder-includes-consult-advisory-test
  (testing "advisory fires on every Nth emit"
    (let [config {:consult/advisory {:every-n-emits 4}}
          ranked [{:card (h/a-card {:id "p1" :tier :principle})
                   :weight 50.0 :recency-fraction 1.0}]]
      (is (str/includes? (ptu/build-reminder ranked 4 config) "consult"))
      (is (not (str/includes? (ptu/build-reminder ranked 5 config) "succession consult"))))))
