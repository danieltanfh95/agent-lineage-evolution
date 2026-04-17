(ns succession.cli.identity-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [succession.cli.identity :as identity]
            [succession.store.cards :as store-cards]
            [succession.store.test-helpers :as h]))

(def ^:dynamic *root* nil)

(defn with-tmp-root [t]
  (let [root (h/tmp-dir! "succession-cli-identity")]
    (binding [*root* root]
      (try (t)
           (finally (h/delete-tree! root))))))

(use-fixtures :each with-tmp-root)

(defn- stdout-of [thunk]
  (with-out-str (thunk)))

(defn- exit-via-system-exit
  "Run thunk expecting it to call System/exit. In Babashka, System/exit
   throws an ExceptionInfo with :babashka/exit. Returns the exit code."
  [thunk]
  (try
    (thunk)
    (catch clojure.lang.ExceptionInfo e
      (if-let [code (:babashka/exit (ex-data e))]
        code
        (throw e)))))

(defn- suppress-stderr
  "Run thunk with *err* redirected to a throwaway writer."
  [thunk]
  (binding [*err* (java.io.PrintWriter. (java.io.StringWriter.))]
    (thunk)))

;; --- list ---

(deftest list-empty-store-test
  (testing "list on empty store prints headers but no card rows"
    (let [exit (atom nil)
          out  (stdout-of (fn [] (reset! exit (identity/run *root* ["list"]))))]
      (is (= 0 @exit))
      (is (str/includes? out "id"))
      (is (str/includes? out "tier"))
      (is (str/includes? out "bounds")))))

(deftest list-populated-test
  (testing "list shows both cards with correct columns"
    (store-cards/write-card! *root*
      (h/a-card {:id "card-a" :tier :rule
                 :tier-bounds {:floor :rule}
                 :text "text a"}))
    (store-cards/write-card! *root*
      (h/a-card {:id "card-b" :tier :ethic :text "text b"}))
    (let [exit (atom nil)
          out  (stdout-of (fn [] (reset! exit (identity/run *root* ["list"]))))]
      (is (= 0 @exit))
      (is (str/includes? out "card-a"))
      (is (str/includes? out "card-b"))
      (is (str/includes? out "floor=rule")))))

;; --- show ---

(deftest show-found-test
  (testing "show prints all fields for an existing card"
    (store-cards/write-card! *root*
      (h/a-card {:id "show-me" :tier :rule
                 :tier-bounds {:floor :rule}
                 :text "hello world"
                 :tags [:a :b]}))
    (let [exit (atom nil)
          out  (stdout-of (fn [] (reset! exit (identity/run *root* ["show" "show-me"]))))]
      (is (= 0 @exit))
      (is (str/includes? out "show-me"))
      (is (str/includes? out "rule"))
      (is (str/includes? out "floor=rule"))
      (is (str/includes? out "hello world"))
      (is (str/includes? out "a, b")))))

(deftest show-unknown-id-test
  (testing "show with unknown card id returns exit 1"
    (let [exit (atom nil)
          _    (stdout-of
                 (fn [] (suppress-stderr
                          (fn [] (reset! exit (identity/run *root* ["show" "nonexistent"]))))))]
      (is (= 1 @exit)))))

;; --- set ---

(deftest set-tier-change-test
  (testing "set --tier moves card to new tier directory and refreshes promoted.edn"
    (store-cards/write-card! *root*
      (h/a-card {:id "movable" :tier :ethic :text "move me"}))
    (let [exit (atom nil)
          _    (stdout-of (fn [] (reset! exit (identity/run *root* ["set" "movable" "--tier" "rule"]))))]
      (is (= 0 @exit))
      (let [cards (store-cards/load-all-cards *root*)
            c     (first (filter #(= "movable" (:card/id %)) cards))]
        (is (= :rule (:card/tier c)))
        (let [snap (store-cards/read-promoted-snapshot *root*)]
          (is (some #(= "movable" (:card/id %)) (:cards snap))))))))

(deftest set-tier-sets-floor-by-default-test
  (testing "--tier rule without --no-bounds sets {:floor :rule}"
    (store-cards/write-card! *root*
      (h/a-card {:id "floor-me" :tier :ethic :text "txt"}))
    (let [exit (atom nil)
          _    (stdout-of (fn [] (reset! exit (identity/run *root* ["set" "floor-me" "--tier" "rule"]))))]
      (is (= 0 @exit))
      (let [c (first (filter #(= "floor-me" (:card/id %))
                             (store-cards/load-all-cards *root*)))]
        (is (= {:floor :rule} (:card/tier-bounds c)))))))

(deftest set-no-bounds-clears-bounds-test
  (testing "--no-bounds removes :card/tier-bounds"
    (store-cards/write-card! *root*
      (h/a-card {:id "unbind" :tier :rule
                 :tier-bounds {:floor :rule}
                 :text "txt"}))
    (let [exit (atom nil)
          _    (stdout-of (fn [] (reset! exit (identity/run *root* ["set" "unbind" "--no-bounds"]))))]
      (is (= 0 @exit))
      (let [c (first (filter #(= "unbind" (:card/id %))
                             (store-cards/load-all-cards *root*)))]
        (is (nil? (:card/tier-bounds c)))))))

(deftest set-tier-floor-only-test
  (testing "--tier-floor only updates floor, keeps current tier"
    (store-cards/write-card! *root*
      (h/a-card {:id "floor-only" :tier :rule :text "txt"}))
    (let [exit (atom nil)
          _    (stdout-of (fn [] (reset! exit (identity/run *root* ["set" "floor-only" "--tier-floor" "ethic"]))))]
      (is (= 0 @exit))
      (let [c (first (filter #(= "floor-only" (:card/id %))
                             (store-cards/load-all-cards *root*)))]
        (is (= :rule (:card/tier c)) "tier unchanged")
        (is (= {:floor :ethic} (:card/tier-bounds c)))))))

(deftest set-invalid-tier-test
  (testing "set with invalid tier exits 1"
    (store-cards/write-card! *root*
      (h/a-card {:id "bad-tier" :tier :rule :text "txt"}))
    (let [code (exit-via-system-exit
                 (fn [] (suppress-stderr
                          (fn [] (identity/run *root* ["set" "bad-tier" "--tier" "legendary"])))))]
      (is (= 1 code)))))

(deftest set-unknown-card-test
  (testing "set with unknown card id exits 1"
    (let [code (exit-via-system-exit
                 (fn [] (suppress-stderr
                          (fn [] (identity/run *root* ["set" "ghost" "--tier" "rule"])))))]
      (is (= 1 code)))))

;; --- dispatch ---

(deftest unknown-subcommand-test
  (testing "unknown subcommand returns exit 1"
    (let [exit (atom nil)
          _    (stdout-of
                 (fn [] (suppress-stderr
                          (fn [] (reset! exit (identity/run *root* ["bogus"]))))))]
      (is (= 1 @exit)))))
