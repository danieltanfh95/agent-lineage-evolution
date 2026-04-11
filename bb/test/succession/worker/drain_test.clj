(ns succession.worker.drain-test
  "Integration test for the drain worker pipeline. Runs `drain/run!`
   in-process with a stubbed handler so the test never forks a real
   `bb` JVM and never calls `claude -p`."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [succession.llm.claude :as claude]
            [succession.store.cards :as store-cards]
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

;; ------------------------------------------------------------------
;; E2E regression — map-shaped tool-response on the real judge path
;;
;; This test reproduces the failure mode that dead-lettered 17 judge
;; jobs on 2026-04-11: Claude Code hands structured maps for
;; tool-input / tool-response, the old `build-tool-prompt` called
;; `subs` on them, and the resulting ClassCastException went silently
;; to dead-letter because no stack trace was captured.
;;
;; The test drives the REAL handle-job! :judge method with the exact
;; payload shape Claude Code produces, stubs `claude/call` so no LLM
;; is actually called, and asserts:
;;
;;   1. the queue drains cleanly (0 pending / 0 inflight / 0 dead)
;;   2. for defence-in-depth: if any job *does* dead-letter (future
;;      regression in a different layer), the sidecar `.error.edn`
;;      carries a non-empty `:trace`, so the failure cannot be silent.
;;
;; The two assertions together form a rule the project can never
;; regress on: map-shaped hook payloads must flow through the judge
;; lane without exception, AND any future handler failure must leave
;; a stack trace on disk.
;; ------------------------------------------------------------------

(defn- seed-one-real-card!
  "Write a real promoted card so handle-job! :judge has something to
   score against. `materialize-promoted!` builds the snapshot the
   judge reads back."
  [root]
  (store-cards/write-card!
    root (h/a-card {:id   "prefer-edit"
                    :tier :rule
                    :text "Prefer Edit over Write for existing files"}))
  (store-cards/materialize-promoted! root))

(defn- enqueue-real-judge-job!
  "Enqueue a judge job with the exact payload shape Claude Code
   produces in post-tool-use: every field is a map."
  [root]
  (jobs/enqueue!
    root
    (jobs/make-job
      {:type         :judge
       :session      "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
       :project-root root
       :payload      {:tool-name     "Read"
                      :tool-input    {:file_path "/tmp/x.clj"}
                      :tool-response {:type "text"
                                      :file {:filePath "/tmp/x.clj"
                                             :content "(ns foo)"}}}}))
  (Thread/sleep 3))

(def ^:private fake-judge-response
  "A canned 'not-applicable' verdict. This is the smallest valid judge
   response the parser accepts — we only care that the prompt-building
   path runs cleanly, not that any observation is written."
  "{\"card_id\":\"none\",\"kind\":\"not-applicable\",\"confidence\":0.9}")

(deftest drain-judge-handles-map-shaped-payload-e2e-test
  (testing "the real handle-job! :judge path survives Claude Code's
            map-shaped tool-input and tool-response, without any
            subs/CharSequence crash"
    (seed-one-real-card! *root*)
    (enqueue-real-judge-job! *root*)
    (let [calls (atom 0)]
      (with-redefs [claude/call (fn [_prompt _opts]
                                  (swap! calls inc)
                                  {:ok?        true
                                   :text       fake-judge-response
                                   :cost-usd   0.0
                                   :latency-ms 1})]
        (let [exit (drain/run! *root* {:config-override test-worker-config})]
          (is (= 0 exit))
          (is (pos? @calls)
              "the real judge handler must reach the stubbed LLM,
               proving the prompt builder no longer crashes on maps")
          (is (= 0 (jobs/count-pending *root*)))
          (is (= 0 (jobs/count-inflight *root*)))
          (is (= 0 (jobs/count-dead *root*))
              "no job dead-lettered — the judge lane is healthy"))))))

(deftest drain-dead-letter-always-has-trace-e2e-test
  (testing "defence-in-depth: ANY handler failure — from any layer,
            in any future regression — must leave a stack trace in the
            sidecar. This is what would have exposed the judge bug on
            the first dead-letter instead of the 17th."
    (enqueue-real-judge-job! *root*)
    (drain/run!
      *root*
      {:handle-fn       (fn [_job]
                          (throw (ex-info "simulated regression"
                                          {:layer :handler})))
       :config-override test-worker-config})
    (is (= 1 (jobs/count-dead *root*)))
    (let [dead     (first (jobs/list-dead *root*))
          dead-dir (io/file (paths/jobs-dead-dir *root*))
          err-file (->> (.listFiles dead-dir)
                        (filter #(str/ends-with? (.getName ^java.io.File %)
                                                 ".error.edn"))
                        first)
          on-disk  (edn/read-string {:readers {}} (slurp err-file))]
      (is (= "simulated regression" (:error/message dead)))
      (is (string? (:error/trace dead)))
      (is (pos? (count (:error/trace dead)))
          "list-dead must expose the trace to CLI tooling")
      (is (string? (:trace on-disk)))
      (is (pos? (count (:trace on-disk)))
          "the on-disk sidecar must carry the trace too, so operators
           inspecting .succession/staging/jobs/dead/*.error.edn never
           face a 17-jobs-with-no-hint situation again"))))
