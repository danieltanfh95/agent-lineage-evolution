(ns succession.store.sessions-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [succession.store.paths :as paths]
            [succession.store.sessions :as sessions]
            [succession.store.staging :as staging]
            [succession.store.test-helpers :as h]))

(def ^:dynamic *root* nil)

;; Use real session-id-shaped UUIDs. staged-sessions now filters by
;; UUID shape to keep infrastructure dirs (jobs/, .inflight/, ...)
;; out of orphan-reconciliation lists.
(def s1-uuid "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
(def s2-uuid "11111111-2222-3333-4444-555555555555")

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
  (stage-something! s1-uuid)
  (stage-something! s2-uuid)
  (is (= #{s1-uuid s2-uuid} (set (sessions/staged-sessions *root*)))))

(deftest staged-sessions-filters-non-uuid-dirs-test
  (testing "infrastructure dirs under staging/ never masquerade as sessions"
    (stage-something! s1-uuid)
    ;; Seed the exact shapes that caused the SessionStart false-positive:
    ;; the async-job queue dir, its inflight shard, a dead-letter shard,
    ;; and a bare 'debug' directory.
    (paths/ensure-dir! (paths/jobs-dir *root*))
    (paths/ensure-dir! (paths/jobs-inflight-dir *root*))
    (paths/ensure-dir! (paths/jobs-dead-dir *root*))
    (paths/ensure-dir! (str (paths/staging-dir *root*) "/debug"))
    (is (= [s1-uuid] (sessions/staged-sessions *root*))
        "only the UUID-shaped directory is a real session")
    (is (= [] (sessions/orphan-staging *root* s1-uuid))
        "and an orphan query for the current session returns nothing")))

(deftest orphan-staging-excludes-current-session-test
  (stage-something! s1-uuid)
  (stage-something! s2-uuid)
  (is (= [s2-uuid] (sessions/orphan-staging *root* s1-uuid)))
  (is (= [s1-uuid] (sessions/orphan-staging *root* s2-uuid)))
  (is (= #{s1-uuid s2-uuid}
         (set (sessions/orphan-staging *root* "brand-new")))))

(deftest has-staging-test
  (is (not (sessions/has-staging? *root* s1-uuid)))
  (stage-something! s1-uuid)
  (is (sessions/has-staging? *root* s1-uuid)))
