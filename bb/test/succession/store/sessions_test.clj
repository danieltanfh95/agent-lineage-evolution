(ns succession.identity.store.sessions-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [succession.identity.store.sessions :as sessions]
            [succession.identity.store.staging :as staging]
            [succession.identity.store.test-helpers :as h]))

(def ^:dynamic *root* nil)

(defn with-tmp-root [t]
  (let [root (h/tmp-dir! "succession-store-sessions")]
    (binding [*root* root]
      (try (t)
           (finally (h/delete-tree! root))))))

(use-fixtures :each with-tmp-root)

(defn- stage-something! [session-id]
  (staging/append-delta!
    *root* session-id
    (staging/make-delta {:id (str "d-" session-id)
                          :at #inst "2026-04-11T12:00:00Z"
                          :kind :create-card
                          :source :judge})))

(deftest staged-sessions-returns-all-test
  (is (= [] (sessions/staged-sessions *root*)))
  (stage-something! "s1")
  (stage-something! "s2")
  (is (= #{"s1" "s2"} (set (sessions/staged-sessions *root*)))))

(deftest orphan-staging-excludes-current-session-test
  (stage-something! "s1")
  (stage-something! "s2")
  (is (= ["s2"] (sessions/orphan-staging *root* "s1")))
  (is (= ["s1"] (sessions/orphan-staging *root* "s2")))
  (is (= #{"s1" "s2"} (set (sessions/orphan-staging *root* "brand-new")))))

(deftest has-staging-test
  (is (not (sessions/has-staging? *root* "s1")))
  (stage-something! "s1")
  (is (sessions/has-staging? *root* "s1")))
