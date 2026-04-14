(ns succession.cli.queue-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [succession.cli.queue :as q]
            [succession.store.jobs :as jobs]
            [succession.store.test-helpers :as h]))

(def ^:dynamic *root* nil)

(defn with-tmp-root [t]
  (let [root (h/tmp-dir! "succession-cli-queue")]
    (binding [*root* root]
      (try (t)
           (finally (h/delete-tree! root))))))

(use-fixtures :each with-tmp-root)

(defn- seed-dead-job! [session]
  (jobs/enqueue! *root* (jobs/make-job {:type :judge :session session
                                        :project-root *root* :payload {}}))
  (let [[job] (jobs/list-pending *root*)]
    (jobs/claim! *root* (:job/filename job))
    (jobs/dead-letter! *root* (:job/filename job) job (ex-info "boom" {}))
    (:job/filename job)))

(defn- seed-pending-job! [session]
  (jobs/enqueue! *root* (jobs/make-job {:type :judge :session session
                                        :project-root *root* :payload {}})))

(defn- stdout-of [thunk]
  (with-out-str (thunk)))

;; ------------------------------------------------------------------
;; status
;; ------------------------------------------------------------------

(deftest queue-status-empty-test
  (let [out (stdout-of (fn [] (q/run *root* ["status"])))]
    (is (str/includes? out "0 pending"))
    (is (str/includes? out "0 inflight"))
    (is (str/includes? out "0 dead"))
    (is (str/includes? out "unlocked"))))

(deftest queue-status-with-dead-test
  (seed-dead-job! "s1")
  (seed-dead-job! "s2")
  (seed-pending-job! "s3")
  (let [out (stdout-of (fn [] (q/run *root* ["status"])))]
    (is (str/includes? out "1 pending"))
    (is (str/includes? out "2 dead"))
    (is (str/includes? out "list-dead")
        "when dead > 0, status hints at list-dead")))

;; ------------------------------------------------------------------
;; list-dead
;; ------------------------------------------------------------------

(deftest queue-list-dead-empty-test
  (let [out (stdout-of (fn [] (q/run *root* ["list-dead"])))]
    (is (str/includes? out "no dead-lettered jobs"))))

(deftest queue-list-dead-renders-rows-test
  (seed-dead-job! "session-one")
  (Thread/sleep 5)
  (seed-dead-job! "session-two")
  (let [out (stdout-of (fn [] (q/run *root* ["list-dead"])))]
    (is (str/includes? out "session-one"))
    (is (str/includes? out "session-two"))
    (is (str/includes? out "judge"))
    (is (str/includes? out "boom"))))

;; ------------------------------------------------------------------
;; requeue
;; ------------------------------------------------------------------

(deftest queue-requeue-single-test
  (let [fname (seed-dead-job! "s1")
        out   (stdout-of (fn [] (q/run *root* ["requeue" fname])))]
    (is (str/includes? out (str "requeued " fname)))
    (is (= 1 (jobs/count-pending *root*)))
    (is (= 0 (jobs/count-dead *root*)))))

(deftest queue-requeue-all-test
  (seed-dead-job! "s1") (Thread/sleep 5)
  (seed-dead-job! "s2") (Thread/sleep 5)
  (seed-dead-job! "s3")
  (let [out (stdout-of (fn [] (q/run *root* ["requeue" "--all"])))]
    (is (str/includes? out "requeued 3"))
    (is (= 3 (jobs/count-pending *root*)))
    (is (= 0 (jobs/count-dead *root*)))))

(deftest queue-requeue-missing-filename-test
  (testing "a missing filename prints to stderr and returns a non-zero
            exit code rather than silently succeeding"
    (let [err (with-out-str
                (binding [*err* *out*]
                  (is (= 1 (q/run *root* ["requeue" "nope.json"])))))]
      (is (str/includes? err "no dead job named")))))

(deftest queue-requeue-no-args-is-usage-error-test
  (let [err (with-out-str
              (binding [*err* *out*]
                (is (= 1 (q/run *root* ["requeue"])))))]
    (is (str/includes? err "usage"))))

;; ------------------------------------------------------------------
;; clear-dead
;; ------------------------------------------------------------------

(deftest queue-clear-dead-all-test
  (seed-dead-job! "s1") (Thread/sleep 5)
  (seed-dead-job! "s2")
  (let [out (stdout-of (fn [] (q/run *root* ["clear-dead"])))]
    (is (str/includes? out "deleted 2"))
    (is (= 0 (jobs/count-dead *root*)))))

(deftest queue-clear-dead-older-than-test
  (seed-dead-job! "s1")
  (testing "a 1-day cutoff leaves fresh files alone"
    (let [out (stdout-of (fn [] (q/run *root* ["clear-dead" "--older-than" "1d"])))]
      (is (str/includes? out "deleted 0"))
      (is (= 1 (jobs/count-dead *root*)))))
  (testing "bogus duration string is a usage error"
    (let [err (with-out-str
                (binding [*err* *out*]
                  (is (= 1 (q/run *root* ["clear-dead" "--older-than" "lol"])))))]
      (is (str/includes? err "usage")))))

;; ------------------------------------------------------------------
;; dispatch errors
;; ------------------------------------------------------------------

(deftest queue-unknown-op-test
  (let [err (with-out-str
              (binding [*err* *out*]
                (is (= 1 (q/run *root* ["nope"])))))]
    (is (str/includes? err "unknown subcommand"))))
