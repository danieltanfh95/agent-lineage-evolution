(ns succession.hook.post-tool-use-test
  "Tests for the pure functions of post-tool-use. The async judge lane
   spawns a subprocess and is not unit-tested here — it's covered by the
   integration shadow-mode run in Phase 2."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [succession.hook.post-tool-use :as ptu]
            [succession.store.test-helpers :as h]))

;; ------------------------------------------------------------------
;; should-emit? — context-size-only gate with burst suppression.
;; No call counting — pacing by bytes and wall-clock only.
;; ------------------------------------------------------------------

(def default-gate
  {:byte-threshold        100000   ; 100KB
   :cold-start-skip-bytes 50000    ; 50KB
   :burst-suppress-ms     1000})   ; 1s

(def now-ms 1000000)

(deftest cold-start-skip-test
  (testing "below cold-start-skip-bytes → no emit"
    (is (not (ptu/should-emit?
               {:emits 0 :last-emit-bytes 0 :last-emit-ms 0}
               49999 now-ms default-gate))))
  (testing "at cold-start threshold, first emit fires"
    (is (ptu/should-emit?
          {:emits 0 :last-emit-bytes 0 :last-emit-ms 0}
          50000 now-ms default-gate))))

(deftest burst-suppression-test
  (testing "emit within burst window → suppressed"
    (is (not (ptu/should-emit?
               {:emits 1 :last-emit-bytes 50000 :last-emit-ms 999500}
               160000 now-ms default-gate))))  ; 500ms since last emit
  (testing "emit outside burst window → allowed"
    (is (ptu/should-emit?
          {:emits 1 :last-emit-bytes 50000 :last-emit-ms 998000}
          160000 now-ms default-gate))))  ; 2s since last emit + 100KB delta

(deftest byte-threshold-test
  (testing "below byte threshold since last emit → no emit"
    (is (not (ptu/should-emit?
               {:emits 1 :last-emit-bytes 50000 :last-emit-ms 0}
               140000 now-ms default-gate))))  ; only 90KB since last
  (testing "at byte threshold since last emit → emit"
    (is (ptu/should-emit?
          {:emits 1 :last-emit-bytes 50000 :last-emit-ms 0}
          150000 now-ms default-gate))))  ; 100KB since last

(deftest no-cap-keeps-firing-test
  (testing "gate keeps emitting past any emit count when byte threshold met"
    (is (ptu/should-emit?
          {:emits 20 :last-emit-bytes 1000000 :last-emit-ms 0}
          1100000 now-ms default-gate))))

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

;; ------------------------------------------------------------------
;; Refresh state roundtrip
;; ------------------------------------------------------------------

(deftest read-state-defaults-when-missing-test
  (testing "fresh session returns the initial state record"
    (let [st (ptu/read-state (str "ptu-test-" (random-uuid)))]
      (is (= 0 (:emits st)))
      (is (= 0 (:last-emit-bytes st)))
      (is (= 0 (:last-emit-ms st))))))
