(ns succession.hook.common-test
  "Tests for the shared hook plumbing — refresh gate and state roundtrip.
   Both PreToolUse and PostToolUse share the same gate and state file,
   so these tests pin the contract in one place."
  (:require [clojure.test :refer [deftest is testing]]
            [succession.hook.common :as common]))

;; ------------------------------------------------------------------
;; should-emit? — byte-delta-only gate. No wall-clock, no call
;; counting. The infinite-context axiom makes time meaningless inside
;; a session; bytes-since-last-emit is the sole pacing signal.
;; ------------------------------------------------------------------

(def default-gate
  {:byte-threshold        200000   ; 200KB
   :cold-start-skip-bytes 50000})  ; 50KB

(deftest cold-start-skip-test
  (testing "below cold-start-skip-bytes → no emit"
    (is (not (common/should-emit?
               {:emits 0 :last-emit-bytes 0}
               49999 default-gate))))
  (testing "at cold-start threshold, first emit fires"
    (is (common/should-emit?
          {:emits 0 :last-emit-bytes 0}
          50000 default-gate))))

(deftest byte-threshold-test
  (testing "below byte threshold since last emit → no emit"
    (is (not (common/should-emit?
               {:emits 1 :last-emit-bytes 60000}
               60000 default-gate))))
  (testing "at byte threshold since last emit → emit"
    (is (common/should-emit?
          {:emits 1 :last-emit-bytes 60000}
          260000 default-gate))))

(deftest parallel-batch-dedup-test
  (testing "second hook in a parallel batch sees last=cur and skips"
    ;; First call emits at 260000, writing last-emit-bytes=260000.
    ;; Second call in the same batch sees cur-bytes=260000 again.
    (is (not (common/should-emit?
               {:emits 2 :last-emit-bytes 260000}
               260000 default-gate)))))

(deftest no-cap-keeps-firing-test
  (testing "gate keeps emitting past any emit count when byte threshold met"
    (is (common/should-emit?
          {:emits 20 :last-emit-bytes 1000000}
          1200000 default-gate))))

;; ------------------------------------------------------------------
;; Refresh state roundtrip
;; ------------------------------------------------------------------

(deftest read-refresh-state-defaults-when-missing-test
  (testing "fresh session returns the initial state record"
    (let [st (common/read-refresh-state (str "common-test-" (random-uuid)))]
      (is (= 0 (:emits st)))
      (is (= 0 (:last-emit-bytes st))))))

(deftest state-roundtrip-test
  (testing "write-then-read returns the same map"
    (let [sid   (str "common-test-" (random-uuid))
          state {:emits 3 :last-emit-bytes 260000}]
      (common/write-refresh-state! sid state)
      (is (= state (common/read-refresh-state sid))))))
