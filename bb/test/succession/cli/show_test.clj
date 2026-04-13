(ns succession.cli.show-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [succession.cli.show :as show]
            [succession.store.cards :as store-cards]
            [succession.store.test-helpers :as h]))

(def ^:dynamic *root* nil)

(defn with-tmp-root [t]
  (let [root (h/tmp-dir! "succession-cli-show")]
    (binding [*root* root]
      (try (t)
           (finally (h/delete-tree! root))))))

(use-fixtures :each with-tmp-root)

(defn- stdout-of
  "Run thunk and return stdout as a string. The CLI prints to *out*
   so we capture there."
  [thunk]
  (with-out-str (thunk)))

(deftest show-empty-store-prints-empty-state-test
  (testing "no promoted cards → the renderer's 'No promoted identity
            cards yet' string, exit 0"
    (let [exit (atom nil)
          out  (stdout-of (fn [] (reset! exit (show/run *root* nil))))]
      (is (= 0 @exit))
      (is (str/includes? out "No promoted identity cards yet")))))

(deftest show-renders-promoted-cards-test
  (testing "two promoted cards → both card ids appear in the markdown
            tree"
    (store-cards/write-card!
      *root* (h/a-card {:id "prefer-edit"
                        :tier :rule
                        :text "Prefer Edit over Write\nfor existing files"}))
    (store-cards/write-card!
      *root* (h/a-card {:id "never-force-push"
                        :tier :principle
                        :text "Never force-push to main\nwithout explicit ok"}))
    (store-cards/materialize-promoted! *root*)
    (let [exit (atom nil)
          out  (stdout-of (fn [] (reset! exit (show/run *root* nil))))]
      (is (= 0 @exit))
      (is (str/includes? out "prefer-edit"))
      (is (str/includes? out "never-force-push"))
      (is (str/includes? out "Mandatory"))
      (is (str/includes? out "Must")))))

(deftest show-format-edn-round-trips-test
  (testing "--format edn prints one pr-str map per card; the first
            character is `{` and every line round-trips via read-string"
    (store-cards/write-card!
      *root* (h/a-card {:id "c1" :tier :rule :text "one"}))
    (store-cards/write-card!
      *root* (h/a-card {:id "c2" :tier :rule :text "two"}))
    (store-cards/materialize-promoted! *root*)
    (let [out (stdout-of (fn [] (show/run *root* ["--format" "edn"])))
          lines (remove str/blank? (str/split-lines out))
          parsed (mapv read-string lines)]
      (is (= 2 (count lines)))
      (is (every? map? parsed))
      (is (= #{"c1" "c2"}
             (set (map :card/id parsed)))))))

(deftest show-parse-args-unknown-flag-is-ignored-test
  (testing "unknown flags don't crash the CLI; we default to markdown"
    (let [out (stdout-of (fn [] (show/run *root* ["--lolwhat"])))]
      (is (string? out)))))
