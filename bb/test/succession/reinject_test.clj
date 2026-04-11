(ns succession.reinject-test
  (:require [clojure.test :refer [deftest is testing]]
            [succession.reinject :as reinject]
            [babashka.fs :as fs]))

(defn- rand-sid [] (str "rt-" (System/nanoTime)))

(deftest hybrid-gate-fires-on-turn-threshold
  (let [sid (rand-sid)
        tmp (str (fs/create-temp-dir {:prefix "reinject-turn-"}))
        tr (str tmp "/transcript.jsonl")]
    (spit tr "small")
    (try
      (testing "10-turn threshold fires on turn 10 with small transcript"
        (is (true? (reinject/should-reinject? sid tr 10 1000000 10))))
      (testing "next fire requires 10 more turns"
        (is (false? (reinject/should-reinject? sid tr 12 1000000 10)))
        (is (false? (reinject/should-reinject? sid tr 19 1000000 10)))
        (is (true? (reinject/should-reinject? sid tr 20 1000000 10))))
      (finally
        (fs/delete-if-exists (str "/tmp/.succession-reinject-state-" sid))))))

(deftest hybrid-gate-fires-on-byte-threshold
  (let [sid (rand-sid)
        tmp (str (fs/create-temp-dir {:prefix "reinject-bytes-"}))
        tr (str tmp "/transcript.jsonl")]
    (try
      (spit tr (apply str (repeat 100 "x")))
      (testing "does not fire below byte threshold and below turn threshold"
        (is (false? (reinject/should-reinject? sid tr 1 1000000 100))))
      (testing "grows past byte threshold → fires"
        (spit tr (apply str (repeat 1100000 "x")))
        (is (true? (reinject/should-reinject? sid tr 2 1000000 100))))
      (testing "after fire, state resets — needs another full threshold to re-fire"
        (is (false? (reinject/should-reinject? sid tr 3 1000000 100))))
      (finally
        (fs/delete-if-exists (str "/tmp/.succession-reinject-state-" sid))))))

(deftest fire-count-cap-bounds-runaway-loop
  (let [sid (rand-sid)
        tmp (str (fs/create-temp-dir {:prefix "reinject-cap-"}))
        tr (str tmp "/transcript.jsonl")]
    (spit tr "x")
    (try
      (testing "cap of 2 — first two fires succeed"
        (is (true? (reinject/should-reinject? sid tr 10 1000000 10 2)))
        (is (true? (reinject/should-reinject? sid tr 20 1000000 10 2))))
      (testing "third fire is suppressed by cap even though gate condition holds"
        (is (false? (reinject/should-reinject? sid tr 30 1000000 10 2)))
        (is (false? (reinject/should-reinject? sid tr 9999 1000000 10 2))))
      (finally
        (fs/delete-if-exists (str "/tmp/.succession-reinject-state-" sid))))))

(deftest build-reinject-context-assembles-bundle
  (let [tmp (str (fs/create-temp-dir {:prefix "reinject-build-"}))]
    (fs/create-dirs (str tmp "/.succession/compiled"))
    (fs/create-dirs (str tmp "/.succession/log"))
    (spit (str tmp "/.succession/compiled/advisory-summary.md") "## ADVISORY\nfoo")
    (spit (str tmp "/.succession/compiled/active-rules-digest.md") "- [advisory] x — y")
    (spit (str tmp "/.succession/log/judge.jsonl")
          (str "{\"verdict\":\"violated\",\"rule_id\":\"r1\",\"retrospective\":\"did the thing\"}\n"))
    (let [bundle (reinject/build-reinject-context tmp {:includeJudgeRetrospectives true
                                                       :maxRetrospectives 5})]
      (is (clojure.string/includes? bundle "## ADVISORY"))
      (is (clojure.string/includes? bundle "ACTIVE RULES DIGEST"))
      (is (clojure.string/includes? bundle "[Succession conscience]"))
      (is (clojure.string/includes? bundle "did the thing")))))
