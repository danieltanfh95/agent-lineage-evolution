(ns succession.store.jobs-test
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [succession.store.jobs :as jobs]
            [succession.store.paths :as paths]
            [succession.store.test-helpers :as h]))

(def ^:dynamic *root* nil)

(defn with-tmp-root [t]
  (let [root (h/tmp-dir! "succession-store-jobs")]
    (binding [*root* root]
      (try (t)
           (finally (h/delete-tree! root))))))

(use-fixtures :each with-tmp-root)

;; ------------------------------------------------------------------
;; Enqueue
;; ------------------------------------------------------------------

(deftest enqueue-writes-parseable-json-test
  (testing "enqueue! writes a json file that list-pending can read back"
    (let [job (jobs/make-job {:type :judge :session "s1"
                              :project-root *root*
                              :payload {:foo "bar"}})
          _   (jobs/enqueue! *root* job)
          pending (jobs/list-pending *root*)]
      (is (= 1 (count pending)))
      (is (= "judge" (:job/type (first pending))))
      (is (= "s1" (:job/session (first pending))))
      (is (= {:foo "bar"} (:job/payload (first pending)))))))

(deftest enqueue-is-fifo-sorted-by-filename-test
  (testing "list-pending returns files in enqueue order"
    (doseq [i [1 2 3 4 5]]
      (jobs/enqueue! *root*
                     (jobs/make-job {:type :judge :session (str "s" i)
                                     :project-root *root*
                                     :payload {:idx i}}))
      (Thread/sleep 5))
    (let [sessions (mapv :job/session (jobs/list-pending *root*))]
      (is (= ["s1" "s2" "s3" "s4" "s5"] sessions)))))

(deftest enqueue-ignores-tmp-files-test
  (testing "stray .tmp files in jobs-dir don't show up in list-pending"
    (let [dir (paths/jobs-dir *root*)]
      (paths/ensure-dir! dir)
      (spit (str dir "/garbage.json.tmp") "half-written")
      (jobs/enqueue! *root* (jobs/make-job {:type :judge :session "s1"
                                            :project-root *root* :payload {}})))
    (is (= 1 (count (jobs/list-pending *root*))))))

;; ------------------------------------------------------------------
;; Claim / complete / dead-letter
;; ------------------------------------------------------------------

(deftest claim-moves-to-inflight-test
  (testing "claim! removes file from jobs/ and places it under .inflight/"
    (jobs/enqueue! *root* (jobs/make-job {:type :judge :session "s1"
                                          :project-root *root* :payload {}}))
    (let [[job] (jobs/list-pending *root*)
          claim-path (jobs/claim! *root* (:job/filename job))]
      (is (some? claim-path))
      (is (= 0 (jobs/count-pending *root*)))
      (is (= 1 (jobs/count-inflight *root*)))
      (is (.exists (io/file claim-path))))))

(deftest claim-twice-returns-nil-test
  (testing "a second claim of the same filename returns nil"
    (jobs/enqueue! *root* (jobs/make-job {:type :judge :session "s1"
                                          :project-root *root* :payload {}}))
    (let [[job] (jobs/list-pending *root*)]
      (is (some? (jobs/claim! *root* (:job/filename job))))
      (is (nil? (jobs/claim! *root* (:job/filename job)))))))

(deftest complete-deletes-inflight-test
  (testing "complete! removes the inflight copy"
    (jobs/enqueue! *root* (jobs/make-job {:type :judge :session "s1"
                                          :project-root *root* :payload {}}))
    (let [[job] (jobs/list-pending *root*)
          _     (jobs/claim! *root* (:job/filename job))]
      (jobs/complete! *root* (:job/filename job))
      (is (= 0 (jobs/count-inflight *root*))))))

(deftest dead-letter-writes-pair-test
  (testing "dead-letter! moves json + writes sibling error.edn"
    (jobs/enqueue! *root* (jobs/make-job {:type :judge :session "s1"
                                          :project-root *root* :payload {}}))
    (let [[job] (jobs/list-pending *root*)
          _     (jobs/claim! *root* (:job/filename job))
          err   (ex-info "boom" {})]
      (jobs/dead-letter! *root* (:job/filename job) job err)
      (let [dead-dir (io/file (paths/jobs-dead-dir *root*))
            names    (sort (map #(.getName %) (.listFiles dead-dir)))]
        (is (= 2 (count names)))
        (is (some #(clojure.string/ends-with? % ".json") names))
        (is (some #(clojure.string/ends-with? % ".error.edn") names))))))

(deftest dead-letter-error-edn-includes-trace-test
  (testing "the .error.edn sidecar includes the stack trace, so future
            silent failures can never recur"
    (jobs/enqueue! *root* (jobs/make-job {:type :judge :session "s1"
                                          :project-root *root* :payload {}}))
    (let [[job] (jobs/list-pending *root*)
          _     (jobs/claim! *root* (:job/filename job))
          err   (ex-info "boom-with-trace" {:why :test})]
      (jobs/dead-letter! *root* (:job/filename job) job err)
      (let [dead-dir (io/file (paths/jobs-dead-dir *root*))
            err-file (->> (.listFiles dead-dir)
                          (filter #(clojure.string/ends-with? (.getName ^java.io.File %)
                                                              ".error.edn"))
                          first)
            data (edn/read-string {:readers {}} (slurp err-file))]
        (is (= "boom-with-trace" (:ex-message data)))
        (is (string? (:trace data)))
        (is (pos? (count (:trace data))))
        (is (.contains ^String (:trace data) "boom-with-trace"))))))

;; ------------------------------------------------------------------
;; Dead-letter inspection / recovery
;; ------------------------------------------------------------------

(defn- seed-dead-job!
  "Helper: enqueue a job, claim it, dead-letter it. Returns the dead
   filename so the test can address it directly."
  [session]
  (jobs/enqueue! *root* (jobs/make-job {:type :judge :session session
                                        :project-root *root* :payload {}}))
  (let [[job] (jobs/list-pending *root*)]
    (jobs/claim! *root* (:job/filename job))
    (jobs/dead-letter! *root* (:job/filename job) job (ex-info "boom" {}))
    (:job/filename job)))

(deftest list-dead-test
  (testing "list-dead enumerates dead jobs and merges sidecar metadata"
    (seed-dead-job! "s1")
    (Thread/sleep 5)
    (seed-dead-job! "s2")
    (let [dead (jobs/list-dead *root*)]
      (is (= 2 (count dead)))
      (is (= ["s1" "s2"] (sort (map :job/session dead))))
      (is (every? :error/message dead))
      (is (every? :error/trace dead))
      (is (every? #(= "boom" (:error/message %)) dead))
      (is (= 2 (jobs/count-dead *root*))))))

(deftest requeue-test
  (testing "requeue! moves a single dead job back into jobs/, drops sidecar"
    (let [fname (seed-dead-job! "s1")
          new-path (jobs/requeue! *root* fname)]
      (is (some? new-path))
      (is (= 1 (jobs/count-pending *root*)))
      (is (= 0 (jobs/count-dead *root*)))
      (let [pending (first (jobs/list-pending *root*))]
        ;; filename preserved → original FIFO position retained
        (is (= fname (:job/filename pending))))
      ;; sidecar must be gone
      (let [dead-dir (io/file (paths/jobs-dead-dir *root*))]
        (is (zero? (count (filter #(clojure.string/ends-with? (.getName ^java.io.File %)
                                                              ".error.edn")
                                  (.listFiles dead-dir))))))))
  (testing "requeue! on a missing filename returns nil, no throw"
    (is (nil? (jobs/requeue! *root* "nope.json")))))

(deftest requeue-all-test
  (testing "requeue-all! moves every dead job"
    (seed-dead-job! "s1") (Thread/sleep 5)
    (seed-dead-job! "s2") (Thread/sleep 5)
    (seed-dead-job! "s3")
    (is (= 3 (jobs/requeue-all! *root*)))
    (is (= 3 (jobs/count-pending *root*)))
    (is (= 0 (jobs/count-dead *root*)))))

(deftest clear-dead-test
  (testing "clear-dead! with nil cutoff wipes everything"
    (seed-dead-job! "s1") (Thread/sleep 5)
    (seed-dead-job! "s2")
    (is (= 2 (jobs/clear-dead! *root* nil)))
    (is (= 0 (jobs/count-dead *root*))))
  (testing "clear-dead! with a cutoff older than any file is a no-op"
    (seed-dead-job! "s3")
    (is (= 0 (jobs/clear-dead! *root* (* 60 60 1000))))
    (is (= 1 (jobs/count-dead *root*)))))

;; ------------------------------------------------------------------
;; Inflight sweep
;; ------------------------------------------------------------------

(deftest sweep-stale-inflight-test
  (testing "stale inflight files get moved back to jobs/ for retry"
    (jobs/enqueue! *root* (jobs/make-job {:type :judge :session "s1"
                                          :project-root *root* :payload {}}))
    (let [[job] (jobs/list-pending *root*)
          _     (jobs/claim! *root* (:job/filename job))
          ifp   (io/file (str (paths/jobs-inflight-dir *root*)
                              "/" (:job/filename job)))]
      ;; age the file 120 seconds into the past
      (.setLastModified ifp (- (System/currentTimeMillis) (* 120 1000)))
      (let [n (jobs/sweep-stale-inflight! *root* 60)]
        (is (= 1 n))
        (is (= 1 (jobs/count-pending *root*)))
        (is (= 0 (jobs/count-inflight *root*)))))))

(deftest sweep-ignores-fresh-inflight-test
  (testing "fresh inflight files are left alone"
    (jobs/enqueue! *root* (jobs/make-job {:type :judge :session "s1"
                                          :project-root *root* :payload {}}))
    (let [[job] (jobs/list-pending *root*)]
      (jobs/claim! *root* (:job/filename job))
      (is (= 0 (jobs/sweep-stale-inflight! *root* 60)))
      (is (= 1 (jobs/count-inflight *root*))))))
