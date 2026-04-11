(ns succession.identity.hook.post-tool-use-test
  "Tests for the pure functions of post-tool-use. The async judge lane
   spawns a subprocess and is not unit-tested here — it's covered by the
   integration shadow-mode run in Phase 2."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [succession.identity.hook.post-tool-use :as ptu]
            [succession.identity.store.test-helpers :as h]))

;; ------------------------------------------------------------------
;; should-emit? — ports `succession.refresh/should-emit?` so we mirror
;; its 18-0 Finding-1 battery semantically.
;; ------------------------------------------------------------------

(def default-gate
  {:integration-gap-turns 2
   :cap-per-session       5
   :byte-threshold        200
   :cold-start-skip-turns 1})

(deftest cold-start-skip-test
  (testing "first call after cold-start gate does not emit until the skip budget is past"
    ;; calls=1 with cold-start-skip-turns=1 means first-emit? is true
    (is (ptu/should-emit?
          {:calls 1 :emits 0 :last-emit-call 0 :last-emit-bytes 0}
          0 default-gate))
    ;; calls=0 means we haven't even reached the skip threshold
    (is (not (ptu/should-emit?
               {:calls 0 :emits 0 :last-emit-call 0 :last-emit-bytes 0}
               0 default-gate)))))

(deftest cap-per-session-test
  (testing "cap blocks further emissions"
    (is (not (ptu/should-emit?
               {:calls 50 :emits 5 :last-emit-call 40 :last-emit-bytes 0}
               10000 default-gate)))))

(deftest integration-gap-test
  (testing "gap too small → no emit"
    (is (not (ptu/should-emit?
               {:calls 5 :emits 1 :last-emit-call 4 :last-emit-bytes 0}
               50 default-gate))))
  (testing "gap met → emit"
    (is (ptu/should-emit?
          {:calls 6 :emits 1 :last-emit-call 4 :last-emit-bytes 0}
          50 default-gate))))

(deftest byte-threshold-test
  (testing "transcript grew enough → emit even without gap"
    (is (ptu/should-emit?
          {:calls 5 :emits 1 :last-emit-call 4 :last-emit-bytes 0}
          500 default-gate))))

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
  (testing "advisory fires on every Nth call"
    (let [config {:consult/advisory {:every-n-turns 4}}
          ranked [{:card (h/a-card {:id "p1" :tier :principle})
                   :weight 50.0 :recency-fraction 1.0}]]
      (is (str/includes? (ptu/build-reminder ranked 4 config) "consult"))
      (is (not (str/includes? (ptu/build-reminder ranked 5 config) "bb succession consult"))))))

;; ------------------------------------------------------------------
;; Refresh state roundtrip
;; ------------------------------------------------------------------

(deftest read-state-defaults-when-missing-test
  (testing "fresh session returns the initial state record"
    (let [st (ptu/read-state (str "ptu-test-" (random-uuid)))]
      (is (= 0 (:calls st)))
      (is (= 0 (:emits st))))))
