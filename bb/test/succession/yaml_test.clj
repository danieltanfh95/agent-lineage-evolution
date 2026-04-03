(ns succession.yaml-test
  (:require [clojure.test :refer [deftest is testing]]
            [succession.yaml :as yaml]
            [babashka.fs :as fs]))

(def sample-rule-content
  "---
id: no-force-push
scope: global
enforcement: mechanical
category: failure-inheritance
type: correction
source:
  session: abc-123
  timestamp: 2026-04-01T10:00:00Z
  evidence: \"User said: never force push\"
overrides: []
enabled: true
effectiveness:
  times_followed: 10
  times_violated: 2
  times_overridden: 0
  last_evaluated: 2026-04-01T10:00:00Z
---

Never force-push without explicit user confirmation.

## Enforcement
- block_bash_pattern: \"git push.*(--force|-f)\"
- reason: \"Force-push blocked — user requires explicit confirmation\"
")

(def old-style-rule-content
  "---
id: old-rule
scope: global
enforcement: advisory
type: preference
source:
  session: test
  timestamp: 2026-01-01T00:00:00Z
  evidence: \"test\"
overrides: []
enabled: true
---

Keep responses concise.
")

(deftest parse-rule-file-test
  (let [tmp (str (fs/create-temp-file {:prefix "rule-" :suffix ".md"}))]
    (spit tmp sample-rule-content)
    (testing "full rule parsing"
      (let [rule (yaml/parse-rule-file tmp)]
        (is (= "no-force-push" (:id rule)))
        (is (= "global" (:scope rule)))
        (is (= "mechanical" (:enforcement rule)))
        (is (= "failure-inheritance" (:category rule)))
        (is (= "correction" (:type rule)))
        (is (= true (:enabled rule)))
        (is (= "abc-123" (get-in rule [:source :session])))
        (is (= 10 (get-in rule [:effectiveness :times-followed])))
        (is (= 2 (get-in rule [:effectiveness :times-violated])))
        (is (= 0 (get-in rule [:effectiveness :times-overridden])))
        (is (string? (:body rule)))
        (is (clojure.string/includes? (:body rule) "Never force-push"))))
    (fs/delete tmp)))

(deftest parse-old-style-rule-test
  (let [tmp (str (fs/create-temp-file {:prefix "rule-" :suffix ".md"}))]
    (spit tmp old-style-rule-content)
    (testing "backwards compatibility defaults"
      (let [rule (yaml/parse-rule-file tmp)]
        (is (= "old-rule" (:id rule)))
        (is (= "strategy" (:category rule)) "category defaults to strategy")
        (is (= 0 (get-in rule [:effectiveness :times-followed])))
        (is (= 0 (get-in rule [:effectiveness :times-violated])))
        (is (nil? (get-in rule [:effectiveness :last-evaluated])))))
    (fs/delete tmp)))

(deftest parse-directives-test
  (testing "extraction of enforcement directives"
    (let [body "Never force-push.\n\n## Enforcement\n- block_bash_pattern: \"git push.*(--force|-f)\"\n- reason: \"Force-push blocked\""
          directives (yaml/parse-directives body)]
      (is (= 1 (count directives)))
      (is (= :block-bash-pattern (:type (first directives))))
      (is (= "git push.*(--force|-f)" (:pattern (first directives))))
      (is (= "Force-push blocked" (:reason (first directives))))))

  (testing "block_tool directive"
    (let [body "No agents.\n\n## Enforcement\n- block_tool: Agent\n- reason: \"Agents blocked\""
          directives (yaml/parse-directives body)]
      (is (= 1 (count directives)))
      (is (= :block-tool (:type (first directives))))
      (is (= "Agent" (:tool (first directives))))))

  (testing "require_prior_read directive"
    (let [body "Read first.\n\n## Enforcement\n- require_prior_read: true\n- reason: \"Must read before edit\""
          directives (yaml/parse-directives body)]
      (is (= 1 (count directives)))
      (is (= :require-prior-read (:type (first directives)))))))

(deftest write-rule-file-round-trip-test
  (let [tmp (str (fs/create-temp-file {:prefix "rule-" :suffix ".md"}))]
    (spit tmp sample-rule-content)
    (let [original (yaml/parse-rule-file tmp)]
      (yaml/write-rule-file tmp original)
      (let [round-tripped (yaml/parse-rule-file tmp)]
        (testing "round-trip preserves id"
          (is (= (:id original) (:id round-tripped))))
        (testing "round-trip preserves enforcement"
          (is (= (:enforcement original) (:enforcement round-tripped))))
        (testing "round-trip preserves category"
          (is (= (:category original) (:category round-tripped))))
        (testing "round-trip preserves effectiveness counters"
          (is (= (get-in original [:effectiveness :times-followed])
                 (get-in round-tripped [:effectiveness :times-followed])))
          (is (= (get-in original [:effectiveness :times-violated])
                 (get-in round-tripped [:effectiveness :times-violated]))))))
    (fs/delete tmp)))
