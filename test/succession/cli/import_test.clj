(ns succession.cli.import-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [succession.cli.import :as import-cli]
            [succession.config :as config]
            [succession.hook.pre-compact :as pre-compact]
            [succession.store.cards :as store-cards]
            [succession.store.test-helpers :as h]))

(def ^:dynamic *root* nil)
(def ^:dynamic *rules-dir* nil)

(defn with-tmp-roots [t]
  (let [root  (h/tmp-dir! "succession-cli-import")
        rules (h/tmp-dir! "succession-cli-import-rules")]
    (binding [*root* root *rules-dir* rules]
      (try (t)
           (finally
             (h/delete-tree! root)
             (h/delete-tree! rules))))))

(use-fixtures :each with-tmp-roots)

(def now #inst "2026-04-11T12:00:00Z")

(def ^:private sample-rule-content
  "---
id: verify-via-repl
category: strategy
type: preference
enforcement: advisory
enabled: true
---

Always verify assumptions via REPL before proposing code.
")

(deftest parse-rule-file-roundtrips-fields-test
  (let [parsed (import-cli/parse-rule-file sample-rule-content)]
    (is (= "verify-via-repl" (:id parsed)))
    (is (= :strategy (:category parsed)))
    (is (re-find #"Always verify" (:text parsed)))))

(deftest parse-rule-file-tolerates-unknown-category-test
  (testing "an unknown category falls back to :strategy"
    (let [content (clojure.string/replace sample-rule-content
                                          "category: strategy"
                                          "category: made-up-thing")
          parsed  (import-cli/parse-rule-file content)]
      (is (= :strategy (:category parsed))))))

(deftest scan-rules-dir-reads-all-md-test
  (spit (io/file *rules-dir* "rule-a.md") sample-rule-content)
  (spit (io/file *rules-dir* "rule-b.md")
        (clojure.string/replace sample-rule-content "verify-via-repl" "another"))
  (let [parsed (import-cli/scan-rules-dir *rules-dir*)]
    (is (= 2 (count parsed)))
    (is (= #{"verify-via-repl" "another"}
           (set (map :id parsed))))))

(deftest stage-imports-and-promote-test
  (spit (io/file *rules-dir* "r1.md") sample-rule-content)
  (let [parsed  (import-cli/scan-rules-dir *rules-dir*)
        session (import-cli/stage-imports! *root* parsed now)]
    (pre-compact/promote! *root* session now config/default-config)
    (let [cards (store-cards/load-all-cards *root*)]
      (is (= 1 (count cards)))
      (is (= "verify-via-repl" (:card/id (first cards))))
      (is (= :ethic (:card/tier (first cards))))
      (is (re-find #"verify" (:card/text (first cards)))))))
