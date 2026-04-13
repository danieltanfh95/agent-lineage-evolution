(ns succession.worker.drain
  "The async drain worker — a single detached `bb` subprocess that
   reads jobs from `staging/jobs/`, dispatches them to handlers, and
   self-exits once the queue has been quiet for `idle-timeout-seconds`.

   This is the *only* place core.async is used in the project. Hooks
   stay synchronous + stdin/stdout-driven; the store layer is pure
   I/O; the handler layer (`llm/judge`, `llm/reconcile`) is unchanged;
   and this namespace wires those together with a bounded
   `pipeline-blocking` so no more than `:parallelism` `claude -p`
   calls run at once.

   Lifecycle:

     1. `run!` acquires `.worker.lock` atomically; losers exit 0.
     2. Sweep `.inflight/` — files older than `:stale-lock-seconds`
        are moved back to `jobs/` so a mid-job crash retries once.
     3. Spawn three `a/thread` loops:
          - scanner — lists pending jobs in FIFO order, claims one,
            writes it to the `jobs-chan` (blocks if the pipeline is
            full).
          - heartbeat — refreshes the lock file mtime so concurrent
            hooks don't see the live worker as stale.
          - idle-watcher — maintains the `last-activity-at` clock and
            closes `jobs-chan` when `domain/queue/idle?` flips true.
     4. `pipeline-blocking` consumes `jobs-chan`, calls `handle-job`
        per job, and writes results to `results-chan`.
     5. Main loop drains `results-chan`: on :ok → `complete!`, on
        :error → `dead-letter!`. When the channel closes, release the
        lock and exit.

   Starvation cannot occur: the scanner always picks the
   lexicographically smallest filename from `jobs/`, and the
   pipeline's bounded parallelism guarantees every job is processed
   within one scan cycle after older jobs start.

   Reference: async-lane plan §Data flow end-to-end, §Why a pipeline
   not plain threads."
  (:require [clojure.core.async :as a]
            [succession.config :as config]
            [succession.domain.queue :as queue]
            [succession.store.jobs :as store-jobs]
            [succession.store.locks :as locks]
            [succession.store.observations :as store-obs]
            [succession.store.paths :as paths])
  (:import (java.util Date)))

;; ------------------------------------------------------------------
;; Handler dispatch
;;
;; Multimethod on `:job/type` (a string on disk; keyed to keyword-
;; or-string here). Adding a new job type means adding one defmethod
;; — no changes to the pipeline itself.
;; ------------------------------------------------------------------

(defmulti handle-job!
  "Run a single job. Takes the parsed job map plus the effective
   identity config. Returns a seq of `{:kind ... :path ...}` side-
   effect records the result map can carry. Throwing is the only
   failure signal — the pipeline catches and routes to dead-letter."
  (fn [job _config] (some-> (:job/type job) name keyword)))

(defmethod handle-job! :default
  [job _config]
  (throw (ex-info (str "unknown job type: " (:job/type job))
                  {:job job})))

(defmethod handle-job! :judge
  [job config]
  (let [payload      (:job/payload job)
        project-root (:job/project-root job)
        session      (or (:job/session job) "unknown")
        now          (Date.)
        cards-ns     (requiring-resolve 'succession.store.cards/read-promoted-snapshot)
        cards        (or (:cards (cards-ns project-root)) [])
        ctx          {:tool-name      (or (:tool-name payload)
                                          (get payload "tool_name"))
                      :tool-input     (or (:tool-input payload)
                                          (get payload "tool_input"))
                      :tool-response  (or (:tool-response payload)
                                          (get payload "tool_response"))
                      :recent-context (or (:recent-context payload)
                                          (get payload "recent_context"))
                      :cards          cards
                      :session       session
                      :at            now
                      :hook          :post-tool-use
                      :id-fn         #(str "obs-judge-" (random-uuid))}
        judge-fn     (requiring-resolve 'succession.llm.judge/judge-tool-call)
        result       (when judge-fn (judge-fn ctx config))]
    (vec
      (for [o (:observations result)]
        (do (store-obs/write-observation! project-root o)
            {:kind :observation-written
             :card-id (:observation/card-id o)})))))

(defmethod handle-job! :llm-reconcile
  [job config]
  (let [project-root (:job/project-root job)
        now          (Date.)
        contra-ns    (requiring-resolve 'succession.store.contradictions/open-contradictions)
        mark-ns      (requiring-resolve 'succession.store.contradictions/mark-resolved!)
        cat2-ns      (requiring-resolve 'succession.llm.reconcile/resolve-category-2)
        cat3p-ns     (requiring-resolve 'succession.llm.reconcile/resolve-category-3-principle)
        cat1-ns      (requiring-resolve 'succession.llm.reconcile/resolve-self-contradictory)
        cat6-ns      (requiring-resolve 'succession.llm.reconcile/resolve-contextual-override)
        auto-ns      (requiring-resolve 'succession.llm.reconcile/auto-applicable?)
        cards-ns     (requiring-resolve 'succession.store.cards/read-promoted-snapshot)
        cards-by-id  (->> (:cards (cards-ns project-root))
                          (into {} (map (juxt :card/id identity))))
        open         (contra-ns project-root)]
    (vec
      (for [c open
            :let [cat    (:contradiction/category c)
                  ctx    {:contradiction c
                          :project-root  project-root
                          :cards-by-id   cards-by-id}
                  result (cond
                           (= cat :semantic-opposition)  (when cat2-ns  (cat2-ns  ctx config))
                           (= cat :principle-violated)   (when cat3p-ns (cat3p-ns ctx config))
                           (= cat :self-contradictory)   (when cat1-ns  (cat1-ns  ctx config))
                           (= cat :contextual-override)  (when cat6-ns  (cat6-ns  ctx config))
                           :else nil)]
            :when (and result (:ok? result) auto-ns
                       (auto-ns (:resolution result) config))]
        (do (mark-ns project-root (:contradiction/id c) :llm-reconcile now
                     (:resolution result))
            {:kind :contradiction-resolved
             :id   (:contradiction/id c)})))))

;; ------------------------------------------------------------------
;; Pipeline orchestration
;; ------------------------------------------------------------------

(defn- now-date [] (Date.))

(defn- worker-config
  "Extract the `:worker/async` section with safe defaults."
  [config]
  (let [merged (merge {:idle-timeout-seconds    10
                       :parallelism             2
                       :stale-lock-seconds      60
                       :heartbeat-seconds       20
                       :scan-interval-ms        500
                       :job-timeout-seconds     90
                       :inflight-sweep-seconds  nil}
                      (:worker/async config))]
    ;; inflight-sweep-seconds defaults to stale-lock-seconds when not set
    (update merged :inflight-sweep-seconds
            #(or % (:stale-lock-seconds merged)))))

(defn- scan-and-claim!
  "One scanner tick: list pending jobs, sort FIFO, claim the head,
   return the claimed job (with `:job/file` pointing at the new
   inflight path), or nil if there's nothing to do / the claim lost
   a race."
  [project-root]
  (when-let [head (first (queue/sort-jobs (store-jobs/list-pending project-root)))]
    (let [fname      (:job/filename head)
          claim-path (store-jobs/claim! project-root fname)]
      (when claim-path
        (assoc head :job/file claim-path)))))

(defn- start-scanner!
  "Scanner thread: polls the jobs dir, pushes claimed jobs onto
   `jobs-chan`. Exits when `stop?` flips true. A successful claim
   resets `last-activity!`."
  [project-root jobs-chan ^clojure.lang.Atom stop?
   ^clojure.lang.Atom last-activity! scan-interval-ms]
  (a/thread
    (try
      (loop []
        (when-not @stop?
          (if-let [job (scan-and-claim! project-root)]
            (do
              (reset! last-activity! (now-date))
              (when-not (a/>!! jobs-chan job)
                ;; channel was closed under us — exit cleanly
                (reset! stop? true)))
            (Thread/sleep (long scan-interval-ms)))
          (when-not @stop? (recur))))
      (catch Throwable t
        (binding [*out* *err*]
          (println "succession drain scanner error:" (.getMessage t)))))))

(defn- start-heartbeat!
  [lock-handle ^clojure.lang.Atom stop? heartbeat-seconds]
  (a/thread
    (try
      (loop []
        (when-not @stop?
          (Thread/sleep (long (* 1000 heartbeat-seconds)))
          (locks/heartbeat! lock-handle)
          (when-not @stop? (recur))))
      (catch Throwable _ nil))))

(defn- start-idle-watcher!
  "Watches the queue state and closes `jobs-chan` when the worker has
   been idle for `idle-timeout-seconds`. Runs on its own cadence,
   driven by `scan-interval-ms` so it picks up changes within one
   scan window.

   Also periodically sweeps stale inflight files back to `jobs/`.
   This recovers jobs orphaned by timed-out futures (where
   `future-cancel` may not have interrupted the handler thread) and
   ensures `inflight-count` eventually drops to 0 so the idle
   predicate can fire."
  [project-root jobs-chan ^clojure.lang.Atom stop?
   ^clojure.lang.Atom last-activity! wcfg]
  (a/thread
    (try
      (let [{:keys [scan-interval-ms inflight-sweep-seconds]} wcfg]
        (loop []
          (when-not @stop?
            (Thread/sleep (long scan-interval-ms))
            ;; Sweep stale inflight files back to jobs/ on every tick.
            ;; The sweep is cheap (one listFiles + mtime check) and
            ;; ensures orphaned jobs don't block idle shutdown.
            (store-jobs/sweep-stale-inflight! project-root inflight-sweep-seconds)
            (let [snapshot {:queue/pending-count    (store-jobs/count-pending project-root)
                            :queue/inflight-count   (store-jobs/count-inflight project-root)
                            :queue/last-activity-at @last-activity!
                            :queue/now              (now-date)}]
              ;; Reset the activity clock whenever there is anything
              ;; to do — idle means queue-empty AND last-activity is
              ;; older than the grace window.
              (when (or (pos? (:queue/pending-count snapshot))
                        (pos? (:queue/inflight-count snapshot)))
                (reset! last-activity! (:queue/now snapshot)))
              (if (queue/idle? snapshot wcfg)
                (do (reset! stop? true)
                    (a/close! jobs-chan))
                (recur))))))
      (catch Throwable _ nil))))

(defn- process-one
  "Handler wrapper run inside the `pipeline-blocking` lane. Catches
   any throwable so the pipeline never dies on a bad job. `handle-fn`
   is normally `(partial handle-job! ??? config)` but tests can inject
   a stub.

   When `timeout-ms` is positive, the handler runs in a future with a
   hard deadline. On timeout the future is cancelled and an ex-info is
   routed through the normal error path (→ dead-letter)."
  [handle-fn job timeout-ms]
  (let [started (now-date)]
    (try
      (if (and timeout-ms (pos? timeout-ms))
        (let [fut    (future (handle-fn job))
              result (deref fut timeout-ms ::timeout)]
          (if (= result ::timeout)
            (do (future-cancel fut)
                (throw (ex-info "job timed out"
                                {:timeout-ms timeout-ms
                                 :job/id     (:job/id job)})))
            (let [finished (now-date)]
              (queue/job->result job nil started finished result))))
        ;; no timeout — run inline
        (let [side-fx  (handle-fn job)
              finished (now-date)]
          (queue/job->result job nil started finished side-fx)))
      (catch java.util.concurrent.ExecutionException ee
        (let [finished (now-date)]
          (queue/job->result job (or (.getCause ee) ee) started finished nil)))
      (catch Throwable t
        (let [finished (now-date)]
          (queue/job->result job t started finished nil))))))

(defn- drain-results!
  "Read every result off `results-chan`, route :ok → complete! and
   :error → dead-letter!. Blocks until the channel closes (which
   happens after `pipeline-blocking` sees its input close)."
  [project-root results-chan]
  (loop []
    (when-let [result (a/<!! results-chan)]
      (let [fname (:result/job-filename result)]
        (case (queue/classify-result result)
          :ok    (store-jobs/complete! project-root fname)
          :error (let [err (:result/error result)
                       ex  (ex-info (or (:message err) "handler error")
                                    {:class (:class err)})]
                   (store-jobs/dead-letter!
                     project-root fname
                     {:job/id (:result/job-id result)
                      :job/filename fname}
                     ex))))
      (recur))))

;; ------------------------------------------------------------------
;; Public entry
;; ------------------------------------------------------------------

(defn run!
  "Drain the queue for `project-root`. Blocks until the worker goes
   idle for the configured grace window, then releases the lock and
   returns an exit code (0 on normal shutdown, 0 also on lock-lost
   since that's not an error).

   If `:handler-override` is supplied via opts, it replaces
   `handle-job!` for the duration of this run — tests use that to
   drive the pipeline without needing real LLM calls."
  ([project-root]
   (run! project-root {}))
  ([project-root {:keys [handle-fn config-override]}]
   (let [cfg       (or config-override (config/load-config project-root))
         wcfg      (worker-config cfg)
         lock-path (paths/jobs-worker-lock project-root)
         lock      (locks/try-lock-at lock-path)]
     (cond
       (nil? lock)
       (if (locks/stale-at? lock-path (:stale-lock-seconds wcfg))
         (do (locks/break-stale-at! lock-path)
             (run! project-root {:handle-fn handle-fn :config-override cfg}))
         0)

       :else
       (let [_              (store-jobs/sweep-stale-inflight! project-root
                                                              (:stale-lock-seconds wcfg))
             jobs-chan      (a/chan (:parallelism wcfg))
             results-chan   (a/chan (:parallelism wcfg))
             stop?          (atom false)
             last-activity! (atom (now-date))
             real-handler   (fn [job] (handle-job! job cfg))
             effective      (or handle-fn real-handler)
             timeout-ms     (* 1000 (:job-timeout-seconds wcfg))
             xform          (map (fn [job] (process-one effective job timeout-ms)))
             _pipe          (a/pipeline-blocking
                              (:parallelism wcfg)
                              results-chan
                              xform
                              jobs-chan)
             _scanner       (start-scanner! project-root jobs-chan stop?
                                            last-activity! (:scan-interval-ms wcfg))
             _idle          (start-idle-watcher! project-root jobs-chan stop?
                                                 last-activity! wcfg)
             _hb            (start-heartbeat! lock stop? (:heartbeat-seconds wcfg))]
         (try
           (drain-results! project-root results-chan)
           0
           (finally
             (reset! stop? true)
             (locks/release! lock))))))))
