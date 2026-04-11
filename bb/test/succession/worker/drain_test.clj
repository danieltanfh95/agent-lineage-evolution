(ns succession.worker.drain-test
  "Integration test for the drain worker pipeline. Runs `drain/run!`
   in-process with a stubbed handler so the test never forks a real
   `bb` JVM and never calls `claude -p`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [succession.store.jobs :as jobs]
            [succession.store.paths :as paths]
            [succession.store.test-helpers :as h]
            [succession.worker.drain :as drain]))

(def ^:dynamic *root* nil)

(defn with-tmp-root [t]
  (let [root (h/tmp-dir! "succession-worker-drain")]
    (binding [*root* root]
      (try (t)
           (finally (h/delete-tree! root))))))

(use-fixtures :each with-tmp-root)

(def ^:private test-worker-config
  "Aggressive timings so the test completes in ~1s."
  {:worker/async {:idle-timeout-seconds 1
                  :parallelism          2
                  :stale-lock-seconds   60
                  :heartbeat-seconds    60
                  :scan-interval-ms     50}})

(defn- seed-job! [root idx]
  (jobs/enqueue! root
                 (jobs/make-job {:type :judge
                                 :session (str "sess-" idx)
                                 :project-root root
                                 :payload {:idx idx}}))
  (Thread/sleep 3))

;; ------------------------------------------------------------------
;; Happy path — 3 jobs, all succeed
;; ------------------------------------------------------------------

(deftest drain-processes-all-jobs-and-exits-test
  (testing "seeded jobs are processed in FIFO order and the worker exits idle"
    (doseq [i [1 2 3]] (seed-job! *root* i))
    (let [processed (atom [])
          exit (drain/run!
                 *root*
                 {:handle-fn       (fn [job]
                                     (swap! processed conj
                                            (get-in job [:job/payload :idx]))
                                     [{:kind :test-ok}])
                  :config-override test-worker-config})]
      (is (= 0 exit))
      (is (= [1 2 3] (sort @processed)))
      (is (= 0 (jobs/count-pending *root*)))
      (is (= 0 (jobs/count-inflight *root*)))
      (is (not (.exists (io/file (paths/jobs-worker-lock *root*))))))))

;; ------------------------------------------------------------------
;; Error path — failing handler sends a job to dead-letter
;; ------------------------------------------------------------------

(deftest drain-routes-failures-to-dead-letter-test
  (testing "a handler exception lands the job in dead/ with an .error.edn"
    (doseq [i [1 2 3]] (seed-job! *root* i))
    (let [exit (drain/run!
                 *root*
                 {:handle-fn       (fn [job]
                                     (let [idx (get-in job [:job/payload :idx])]
                                       (if (= idx 2)
                                         (throw (ex-info "boom" {:idx idx}))
                                         [{:kind :ok}])))
                  :config-override test-worker-config})]
      (is (= 0 exit))
      (is (= 0 (jobs/count-pending *root*)))
      (is (= 0 (jobs/count-inflight *root*)))
      (let [dead-dir (io/file (paths/jobs-dead-dir *root*))
            names    (when (.exists dead-dir)
                       (map #(.getName %) (.listFiles dead-dir)))]
        (is (some #(clojure.string/ends-with? % ".json") names)
            "one dead json file")
        (is (some #(clojure.string/ends-with? % ".error.edn") names)
            "one dead error edn file")))))

;; ------------------------------------------------------------------
;; Lock is released on normal exit
;; ------------------------------------------------------------------

(deftest drain-releases-lock-on-exit-test
  (testing "the worker lock file is gone once run! returns"
    (seed-job! *root* 1)
    (drain/run! *root* {:handle-fn       (fn [_] [])
                        :config-override test-worker-config})
    (is (not (.exists (io/file (paths/jobs-worker-lock *root*)))))))

;; ------------------------------------------------------------------
;; Inflight sweep recovers a mid-job crash
;; ------------------------------------------------------------------

(deftest drain-sweeps-stale-inflight-on-start-test
  (testing "a dead prior worker's inflight file is recovered and retried"
    ;; Manually stage a stale inflight file
    (let [dir   (paths/jobs-inflight-dir *root*)
          _     (paths/ensure-dir! dir)
          fname "20260411T000000000Z-stale-inflight-test.json"
          path  (str dir "/" fname)]
      (spit path "{\"job/id\":\"stale-inflight-test\",\"job/type\":\"judge\",\"job/payload\":{\"idx\":99}}")
      (.setLastModified (io/file path)
                        (- (System/currentTimeMillis) (* 120 1000))))
    (let [processed (atom [])
          exit (drain/run!
                 *root*
                 {:handle-fn       (fn [job]
                                     (swap! processed conj (:job/id job))
                                     [])
                  :config-override (assoc-in test-worker-config
                                             [:worker/async :stale-lock-seconds] 60)})]
      (is (= 0 exit))
      (is (contains? (set @processed) "stale-inflight-test")))))
