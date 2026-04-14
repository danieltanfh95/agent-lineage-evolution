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
            [clojure.string :as str]
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
        resolve-ns   (requiring-resolve 'succession.llm.reconcile/resolve-open!)
        auto-ns      (requiring-resolve 'succession.llm.reconcile/auto-applicable?)
        cards-ns     (requiring-resolve 'succession.store.cards/read-promoted-snapshot)
        cards-by-id  (->> (:cards (cards-ns project-root))
                          (into {} (map (juxt :card/id identity))))
        max-batch    (or (get-in config [:reconcile/llm :max-batch-size]) 10)
        open         (vec (take max-batch (contra-ns project-root)))
        results      (resolve-ns open cards-by-id config)]
    (vec
      (for [{:keys [contradiction-id resolution ok?]} results
            :when (and ok? auto-ns (auto-ns resolution config))]
        (do (mark-ns project-root contradiction-id :llm-reconcile now resolution)
            {:kind :contradiction-resolved
             :id   contradiction-id})))))

;; ------------------------------------------------------------------
;; Structured logging
;; ------------------------------------------------------------------

(defn- ts-now
  "Current time as ISO-8601 UTC string, truncated to seconds."
  []
  (str/replace (str (java.time.Instant/now)) #"\.\d+Z$" "Z"))

(defn- kw->str
  "Convert a keyword to its full string form, including namespace if present.
   :worker/start → \"worker/start\", :pending → \"pending\"."
  [k]
  (if (namespace k)
    (str (namespace k) "/" (name k))
    (name k)))

(def ^:private level-rank
  "Numeric rank for log levels. Used to gate writes below the configured minimum."
  {:debug 0 :info 1 :warn 2 :error 3})

(defn- write-log!
  "Append one structured line to `log-path`. `kvs` is a flat seq of
   key/value pairs (not variadic — callers collect & then pass).
   Format: <ts> [LEVEL] event key=val key=val ...
   Spit failure is silently swallowed — log failure must never crash
   the worker."
  [log-path level event kvs]
  (let [kv-str (when (seq kvs)
                 (str " " (str/join " " (map (fn [[k v]] (str (kw->str k) "=" v))
                                             (partition 2 kvs)))))
        line   (str (ts-now) " [" (format "%-5s" (str/upper-case (name level))) "] "
                    (kw->str event) kv-str "\n")]
    (try (spit log-path line :append true)
         (catch Throwable _ nil))))

;; ------------------------------------------------------------------
;; Pipeline orchestration
;; ------------------------------------------------------------------

(defn- now-date [] (Date.))

(defn- worker-config
  "Extract the `:worker/async` section with safe defaults.

   `stale-lock-seconds` — how long the worker lock can go un-heartbeated
   before another worker considers it stale (heartbeat fires every
   `:heartbeat-seconds`, so this should be >> heartbeat-seconds).

   `inflight-sweep-seconds` — how long a job file can sit in `.inflight/`
   before the sweep assumes the worker that claimed it has died and moves
   it back to `jobs/`. Must be > max expected job duration (LLM calls
   can take 3-4 min). Setting it equal to `stale-lock-seconds` (60s)
   causes live long-running jobs to be recycled mid-flight, creating
   infinite claim loops.

   `max-attempts` — lifetime claim limit per job. Exceeded → dead-letter.
   `max-attempts-per-hour` — rolling 60-min claim limit. Exceeded → dead-letter.
   Both are circuit-breakers against claim loops regardless of their cause."
  [config]
  (merge {:idle-timeout-seconds    30
          :parallelism              2
          :stale-lock-seconds       90
          :heartbeat-seconds        20
          :scan-interval-ms         500
          :inflight-sweep-seconds   600
          :max-attempts             10
          :max-attempts-per-hour     5}
         (:worker/async config)))

(defn- claims-in-window
  "Count how many timestamps in `ts-vec` (ISO-8601 strings) fall within
   the last `window-seconds`. Unparseable entries are ignored."
  [ts-vec window-seconds]
  (let [cutoff-ms (- (System/currentTimeMillis) (* 1000 window-seconds))]
    (count (filter (fn [ts]
                     (try (> (.toEpochMilli (java.time.Instant/parse ts)) cutoff-ms)
                          (catch Throwable _ false)))
                   ts-vec))))

(defn- scan-and-claim!
  "One scanner tick: list pending jobs, sort FIFO, claim the head,
   record the claim (increment attempts + append timestamp), run
   circuit-breaker checks, and return the job (with `:job/file`).

   Returns nil when there is nothing pending, the claim lost a race,
   or the circuit-breaker tripped (in which case the job has already
   been moved to dead-letter so the next tick picks the following job).

   `log!` is a level-gated closure over the log path — signature
   `[level event & kvs]`."
  [project-root wcfg log!]
  (when-let [head (first (queue/sort-jobs (store-jobs/list-pending project-root)))]
    (let [fname      (:job/filename head)
          claim-path (store-jobs/claim! project-root fname)]
      (when claim-path
        (let [job      (or (store-jobs/record-claim! claim-path)
                           (assoc head :job/file claim-path))
              attempts (or (:job/attempts job) 1)
              recent   (claims-in-window
                         (or (:job/claim-timestamps job) []) 3600)
              max-life (or (:max-attempts wcfg) 10)
              max-hour (or (:max-attempts-per-hour wcfg) 5)]
          (cond
            (>= attempts max-life)
            (do (log! :error :job/circuit-break
                      :job fname :reason "lifetime-limit" :attempts attempts)
                (store-jobs/dead-letter!
                  project-root fname
                  (assoc job :job/filename fname)
                  (ex-info "job exceeded max-attempts"
                           {:attempts attempts :max max-life}))
                nil)

            (>= recent max-hour)
            (do (log! :error :job/circuit-break
                      :job fname :reason "hourly-limit" :recent recent)
                (store-jobs/dead-letter!
                  project-root fname
                  (assoc job :job/filename fname)
                  (ex-info "job exceeded max-attempts-per-hour"
                           {:recent recent :max max-hour}))
                nil)

            :else
            (assoc job :job/file claim-path :job/filename fname)))))))

(defn- start-scanner!
  "Scanner thread: polls the jobs dir, pushes claimed jobs onto
   `jobs-chan`. Exits when `stop?` flips true. A successful claim
   resets `last-activity!`. Circuit-breaker violations are routed to
   dead-letter inside `scan-and-claim!` and appear as a nil return,
   so the scanner immediately tries the next job.

   `log!` is a level-gated closure — signature `[level event & kvs]`."
  [project-root jobs-chan ^clojure.lang.Atom stop?
   ^clojure.lang.Atom last-activity! wcfg log!]
  (a/thread
    (try
      (let [scan-interval-ms (:scan-interval-ms wcfg)]
        (loop []
          (when-not @stop?
            (if-let [job (scan-and-claim! project-root wcfg log!)]
              (do
                (log! :info :scanner/claimed
                      :job (:job/filename job) :type (:job/type job))
                (reset! last-activity! (now-date))
                (when-not (a/>!! jobs-chan job)
                  ;; channel was closed under us — exit cleanly
                  (reset! stop? true)))
              (do
                (log! :debug :scanner/tick
                      :pending (store-jobs/count-pending project-root) :claimed "nil")
                (Thread/sleep (long scan-interval-ms))))
            (when-not @stop? (recur)))))
      (catch Throwable t
        (log! :error :scanner/error
              :class (.getName (class t))
              :msg (or (.getMessage t) ""))))))

(defn- start-heartbeat!
  "`log!` is a level-gated closure — signature `[level event & kvs]`."
  [lock-handle ^clojure.lang.Atom stop? heartbeat-seconds log!]
  (a/thread
    (try
      (loop []
        (when-not @stop?
          (Thread/sleep (long (* 1000 heartbeat-seconds)))
          (locks/heartbeat! lock-handle)
          (when-not @stop? (recur))))
      (catch Throwable t
        (log! :error :heartbeat/error
              :class (.getName (class t))
              :msg (or (.getMessage t) ""))))))

(defn- start-idle-watcher!
  "Watches the queue state and closes `jobs-chan` when the worker has
   been idle for `idle-timeout-seconds`. Runs on its own cadence,
   driven by `scan-interval-ms` so it picks up changes within one
   scan window.

   Also periodically sweeps stale inflight files back to `jobs/`.
   This recovers jobs orphaned by timed-out futures (where
   `future-cancel` may not have interrupted the handler thread) and
   ensures `inflight-count` eventually drops to 0 so the idle
   predicate can fire.

   `log!` is a level-gated closure — signature `[level event & kvs]`."
  [project-root jobs-chan ^clojure.lang.Atom stop?
   ^clojure.lang.Atom last-activity! wcfg log!]
  (a/thread
    (try
      (let [{:keys [scan-interval-ms]} wcfg]
        (loop []
          (when-not @stop?
            (Thread/sleep (long scan-interval-ms))
            ;; No inflight sweep here — sweep runs once at worker startup
            ;; (see run!) so it only targets files from a *previous* crashed
            ;; worker. Running it on every tick caused active jobs whose
            ;; inflight mtime was old (claim! preserves enqueue mtime via
            ;; ATOMIC_MOVE) to be swept back to pending while still running,
            ;; producing an infinite claim loop.
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
                (do
                  (log! :info :idle/fire
                        :pending (:queue/pending-count snapshot)
                        :inflight (:queue/inflight-count snapshot))
                  (reset! stop? true)
                  (a/close! jobs-chan))
                (recur))))))
      (catch Throwable t
        (log! :error :idle/watcher-error
              :class (.getName (class t))
              :msg (or (.getMessage t) ""))))))

(defn- process-one
  "Handler wrapper run inside the `pipeline-blocking` lane. Catches
   any throwable so the pipeline never dies on a bad job. `handle-fn`
   is normally `(partial handle-job! ??? config)` but tests can inject
   a stub.

   Runs inline — `pipeline-blocking` workers are already OS threads
   designed for blocking calls. Each transport (opencode, claude) owns
   its own subprocess timeout via `process/process + destroy-tree`, so
   there is no need for a `future` wrapper here. Adding one caused a
   Babashka threading deadlock: the future's thread pool saturated when
   every pipeline worker was simultaneously waiting on a slow
   subprocess, preventing `deref` from ever unblocking."
  [handle-fn job _log!]
  (let [started (now-date)]
    (try
      (let [side-fx  (handle-fn job)
            finished (now-date)]
        (queue/job->result job nil started finished side-fx))
      (catch Throwable t
        (let [finished (now-date)]
          (queue/job->result job t started finished nil))))))

(defn- drain-results!
  "Read every result off `results-chan`, route :ok → complete! and
   :error → dead-letter!. Blocks until the channel closes (which
   happens after `pipeline-blocking` sees its input close).

   `log!` is a level-gated closure — signature `[level event & kvs]`."
  [project-root results-chan log!]
  (loop []
    (when-let [result (a/<!! results-chan)]
      (let [fname       (:result/job-filename result)
            started     (:result/started-at result)
            finished    (:result/finished-at result)
            duration-ms (when (and started finished)
                          (- (.getTime ^java.util.Date finished)
                             (.getTime ^java.util.Date started)))]
        (case (queue/classify-result result)
          :ok
          (do
            (log! :info :job/complete
                  :job fname :duration (str duration-ms "ms"))
            (store-jobs/complete! project-root fname))
          :error
          (let [err (:result/error result)
                ex  (ex-info (or (:message err) "handler error")
                             {:class (:class err)})]
            (log! :error :job/error
                  :job fname
                  :class (or (:class err) "")
                  :msg (or (:message err) ""))
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
       (let [log-path       (paths/jobs-worker-log project-root)
             min-level      (get cfg :worker/log-level :info)
             log!           (fn [level event & kvs]
                              (when (>= (get level-rank level 1)
                                        (get level-rank min-level 1))
                                (write-log! log-path level event kvs)))
             _              (log! :info :worker/start
                                  :parallelism (:parallelism wcfg))
             _              (store-jobs/sweep-stale-inflight! project-root
                                                              (:stale-lock-seconds wcfg))
             jobs-chan      (a/chan (:parallelism wcfg))
             results-chan   (a/chan (:parallelism wcfg))
             stop?          (atom false)
             last-activity! (atom (now-date))
             real-handler   (fn [job] (handle-job! job cfg))
             effective      (or handle-fn real-handler)
             xform          (map (fn [job] (process-one effective job log!)))
             _pipe          (a/pipeline-blocking
                              (:parallelism wcfg)
                              results-chan
                              xform
                              jobs-chan)
             _scanner       (start-scanner! project-root jobs-chan stop?
                                            last-activity! wcfg log!)
             _idle          (start-idle-watcher! project-root jobs-chan stop?
                                                 last-activity! wcfg log!)
             _hb            (start-heartbeat! lock stop? (:heartbeat-seconds wcfg) log!)]
         (try
           (drain-results! project-root results-chan log!)
           0
           (finally
             (log! :info :worker/exit)
             (reset! stop? true)
             (locks/release! lock))))))))
