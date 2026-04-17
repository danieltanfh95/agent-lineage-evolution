(ns succession.worker.drain-test
  "Integration test for the drain worker pipeline. Runs `drain/run!`
   in-process with a stubbed handler so the test never forks a real
   `bb` JVM and never calls `claude -p`."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [succession.llm.reconcile :as reconcile]
            [succession.llm.transport :as transport]
            [succession.store.cards :as store-cards]
            [succession.store.contradictions :as store-contra]
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
  {:worker/async {:idle-timeout-seconds   1
                  :parallelism            2
                  :stale-lock-seconds     60
                  :heartbeat-seconds      60
                  :scan-interval-ms       50
                  :inflight-sweep-seconds 60}})

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
                                             [:worker/async :inflight-sweep-seconds] 60)})]
      (is (= 0 exit))
      (is (contains? (set @processed) "stale-inflight-test")))))

;; ------------------------------------------------------------------
;; Regression — startup sweep uses :inflight-sweep-seconds, not
;; :stale-lock-seconds. These keys measure different things: stale-lock
;; protects the worker lock (60–90s), while inflight-sweep must outlast
;; the longest LLM call (600s). Wiring the wrong key recycles live jobs
;; mid-flight and produces infinite claim loops.
;; ------------------------------------------------------------------

(deftest drain-startup-sweep-uses-inflight-sweep-seconds-test
  (testing "sweep-stale-inflight! is called with :inflight-sweep-seconds"
    (seed-job! *root* 1)
    (let [captured (atom [])]
      (with-redefs [jobs/sweep-stale-inflight!
                    (fn [_root seconds]
                      (swap! captured conj seconds)
                      0)]
        (drain/run!
          *root*
          {:handle-fn       (fn [_] [])
           :config-override (assoc-in test-worker-config
                                      [:worker/async :inflight-sweep-seconds] 600)}))
      (is (= [600] @captured)
          "startup sweep threshold must match :inflight-sweep-seconds, not :stale-lock-seconds"))))

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
      (with-redefs [transport/call (fn [_prompt _opts]
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

;; ------------------------------------------------------------------
;; Structured worker log is written with expected events
;; ------------------------------------------------------------------

(deftest drain-writes-worker-log-test
  (testing "drain worker writes a structured log file containing start/complete/exit"
    (doseq [i [1 2]] (seed-job! *root* i))
    (drain/run!
      *root*
      {:handle-fn       (fn [_] [{:kind :test-ok}])
       :config-override test-worker-config})
    (let [log-path (paths/jobs-worker-log *root*)
          log-file (io/file log-path)]
      (is (.exists log-file) "worker log file should be created by drain/run!")
      (let [contents (slurp log-path)]
        (is (str/includes? contents "worker/start")  "log must contain worker/start")
        (is (str/includes? contents "job/complete")   "log must contain job/complete for each job")
        (is (str/includes? contents "worker/exit")   "log must contain worker/exit")))))

;; ------------------------------------------------------------------
;; Circuit-breaker: job exceeding max-attempts is dead-lettered
;; ------------------------------------------------------------------

(def ^:private circuit-breaker-config
  "Aggressive limits so the test trips the breaker on the second claim."
  {:worker/async {:idle-timeout-seconds   1
                  :parallelism            1
                  :stale-lock-seconds     60
                  :heartbeat-seconds      60
                  :scan-interval-ms       50
                  :inflight-sweep-seconds 600
                  :max-attempts           1       ; trip after 1st claim
                  :max-attempts-per-hour  99}})   ; hourly limit not under test here

(deftest drain-circuit-breaker-lifetime-test
  (testing "a job that has been claimed more than max-attempts times is dead-lettered"
    (seed-job! *root* 1)
    ;; Run once — job is claimed (attempts=1), which equals max-attempts=1,
    ;; so it gets dead-lettered immediately without dispatching to the handler.
    (let [handled (atom 0)
          exit    (drain/run!
                    *root*
                    {:handle-fn       (fn [_] (swap! handled inc) [])
                     :config-override circuit-breaker-config})]
      (is (= 0 exit))
      (is (= 0 @handled)        "handler must NOT be called — breaker trips before dispatch")
      (is (= 0 (jobs/count-pending *root*)))
      (is (= 1 (jobs/count-dead *root*)) "job must land in dead-letter")
      (let [log-contents (slurp (paths/jobs-worker-log *root*))]
        (is (str/includes? log-contents "job/circuit-break") "log must record the trip")
        (is (str/includes? log-contents "lifetime-limit"))))))

;; ------------------------------------------------------------------
;; Batch reconcile — fast drain when no open contradictions
;; ------------------------------------------------------------------

(defn- seed-reconcile-job! [root idx]
  (jobs/enqueue! root
                 (jobs/make-job {:type         :llm-reconcile
                                 :session      (str "sess-" idx)
                                 :project-root root
                                 :payload      {}}))
  (Thread/sleep 3))

(deftest drain-reconcile-batch-completes-when-no-open-contradictions
  (testing "llm-reconcile jobs with no open contradictions complete in ~1s (no LLM call)"
    (doseq [i [1 2 3]] (seed-reconcile-job! *root* i))
    (let [exit (drain/run!
                 *root*
                 {:handle-fn       (fn [_] [])
                  :config-override test-worker-config})]
      (is (= 0 exit))
      (is (= 0 (jobs/count-pending *root*)))
      (is (= 0 (jobs/count-dead *root*))))))

;; ------------------------------------------------------------------
;; Batch reconcile — resolve-open! called exactly once per job
;; ------------------------------------------------------------------

(deftest drain-reconcile-batch-processes-all-in-one-call
  (testing "handle-job! :llm-reconcile calls resolve-open! once for all contradictions"
    (seed-reconcile-job! *root* 1)
    (let [call-count (atom 0)
          call-input (atom nil)]
      (with-redefs [reconcile/resolve-open!
                    (fn [contradictions _cards-by-id _config]
                      (swap! call-count inc)
                      (reset! call-input contradictions)
                      [])
                    store-contra/open-contradictions
                    (fn [_root]
                      [{:contradiction/id       "c-test-1"
                        :contradiction/category :self-contradictory
                        :contradiction/between  [{:card/id "card-1"}]
                        :contradiction/resolved-at nil}
                       {:contradiction/id       "c-test-2"
                        :contradiction/category :contextual-override
                        :contradiction/between  [{:card/id "card-1"}]
                        :contradiction/resolved-at nil}])
                    store-cards/read-promoted-snapshot
                    (fn [_root] {:cards []})]
        (drain/run! *root* {:config-override test-worker-config})
        (is (= 1 @call-count)
            "resolve-open! must be called exactly once, not once per contradiction")
        (is (= 2 (count @call-input))
            "all open contradictions are passed together in one batch")))))

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
