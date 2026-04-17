(ns succession.hook.common
  "Shared plumbing used by every hook entry point.

   The hook entry contract, per plan §The cycle, hook by hook:

     - read one JSON object from *in* (the Claude Code hook payload)
     - derive `project-root` from the `:cwd` field (never fall back to
       `user.dir` inside a hook — the harness tells us where the user is)
     - load config from `<project-root>/.succession/config.edn`
     - do hook-specific work
     - emit zero or one JSON object on *out* (the hookSpecificOutput)

   This namespace centralizes the read/emit/score bits so individual
   hook files can focus on their domain logic. Nothing here is load-
   bearing for tests — the hook tests exercise the hook's `run` fn
   directly with synthetic stdin.

   Reference: `.plans/succession-identity-cycle.md` §Layer split
   (hook/ imports from domain+store+llm), §Data flow."
  (:require [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [succession.config :as config]
            [succession.domain.rollup :as rollup]
            [succession.domain.weight :as weight]
            [succession.store.jobs :as store-jobs]
            [succession.store.locks :as locks]
            [succession.store.observations :as store-obs]
            [succession.store.paths :as paths]))

;; ------------------------------------------------------------------
;; stdin / stdout
;; ------------------------------------------------------------------

(defn read-input
  "Parse one JSON object from *in*. Returns an empty map on parse
   failure — hooks must be fail-safe and never throw uncaught."
  []
  (try
    (let [raw (slurp *in*)]
      (if (str/blank? raw) {} (json/parse-string raw true)))
    (catch Throwable _ {})))

(defn emit-additional-context!
  "Print a `{:hookSpecificOutput {:hookEventName ... :additionalContext text}}`
   JSON blob to stdout. No-op when `text` is blank — Claude Code is happy
   with an empty stdout and we avoid noise in the transcript."
  [hook-event-name text]
  (when (and text (not (str/blank? text)))
    (println
      (json/generate-string
        {:hookSpecificOutput
         {:hookEventName     hook-event-name
          :additionalContext text}}))))

(defn project-root
  "Derive the project root for this hook invocation. Walks up from `:cwd`
   to find the nearest directory containing `.succession/config.edn` or
   `.git` — prevents split queues when cwd is a subdirectory (e.g. bb/).
   Falls back to raw `:cwd`, then `user.dir` for local testing.

   Logs the resolved root to stderr so split-queue bugs are immediately
   visible in Claude Code's hook stderr capture."
  [input]
  (let [cwd  (or (:cwd input) (System/getProperty "user.dir") ".")
        root (loop [d (io/file cwd)]
               (cond
                 (nil? d)
                 cwd
                 (.exists (io/file d ".succession" "config.edn"))
                 (.getPath d)
                 (.exists (io/file d ".git"))
                 (.getPath d)
                 :else
                 (recur (.getParentFile d))))]
    (when (System/getenv "SUCCESSION_DEBUG")
      (binding [*out* *err*]
        (println (str "[succession] project-root: " root " (cwd: " cwd ")"))))
    root))

(defn load-config
  "Load the effective config for the hook. Cached per-invocation by the
   caller if needed; this fn does a single disk read."
  [input]
  (config/load-config (project-root input)))

;; ------------------------------------------------------------------
;; Refresh gate — shared by PreToolUse and PostToolUse. Pacing by
;; transcript-bytes only; wall-clock is meaningless inside an
;; effectively-infinite-context session. The state file is session-
;; scoped and lives in /tmp so it does not pollute the project tree.
;; Parallel tool batches dedup naturally: cur-bytes doesn't grow
;; between the N PreToolUse invocations fired before the tools run,
;; so the delta check `cur-bytes - last-emit-bytes >= byte-threshold`
;; trips exactly once per batch.
;;
;; State shape: `{:emits N :last-emit-bytes N}`
;; ------------------------------------------------------------------

(def ^:private refresh-initial-state
  {:emits 0 :last-emit-bytes 0})

(defn- refresh-state-file [session-id]
  (str "/tmp/.succession-identity-refresh-" session-id))

(defn read-refresh-state
  [session-id]
  (let [f (io/file (refresh-state-file session-id))]
    (if (.exists f)
      (try (edn/read-string (slurp f))
           (catch Throwable _ refresh-initial-state))
      refresh-initial-state)))

(defn write-refresh-state! [session-id state]
  (spit (refresh-state-file session-id) (pr-str state)))

(defn transcript-bytes
  "Byte size of the Claude Code transcript file; 0 when absent."
  [transcript-path]
  (if (and transcript-path (fs/exists? transcript-path))
    (fs/size transcript-path)
    0))

(defn should-emit?
  "Gate decision — pure fn of (state, cur-bytes, gate-config).

   Two gates:
   1. Cold start — haven't reached `:cold-start-skip-bytes` yet, skip
   2. Byte threshold — `:byte-threshold` bytes since last emit

   No wall-clock suppression; bytes-since-last-emit is the sole signal
   the infinite-context principle permits."
  [{:keys [emits last-emit-bytes]}
   cur-bytes
   {:keys [byte-threshold cold-start-skip-bytes]
    :or   {byte-threshold        200000
           cold-start-skip-bytes 50000}}]
  (cond
    ;; Cold start — haven't reached threshold yet
    (< cur-bytes cold-start-skip-bytes)
    false

    ;; First emit — past cold start, never emitted
    (zero? (or emits 0))
    true

    ;; Later emits — byte-threshold since last emit
    :else
    (>= (- cur-bytes (or last-emit-bytes 0)) byte-threshold)))

;; ------------------------------------------------------------------
;; Card metrics — used by post_tool_use salience, pre_compact retier,
;; stop reconcile. Duplicating this logic in each hook would be dumb,
;; so we centralize here.
;; ------------------------------------------------------------------

(defn metrics-for
  "Compute `{:weight :violation-rate :gap-crossings}` for a card given
   its observation seq + the current time + effective config.

   All three are inputs to `domain/tier/propose-transition`."
  [observations now config]
  (let [r       (rollup/rollup-by-session observations)
        w       (weight/compute r now config)
        vr      (rollup/violation-rate r)
        gaps    (weight/gap-crossings r config)]
    {:weight         w
     :violation-rate vr
     :gap-crossings  gaps}))

(defn- recency-fraction
  "0..1 fraction of freshness. 1 = just observed, 0 = older than the
   decay half-life."
  [rollup-map now half-life-days]
  (if (empty? rollup-map)
    0.0
    (let [last-at (reduce #(if (pos? (compare %1 %2)) %1 %2)
                          (map :session/last-at (vals rollup-map)))
          age-ms  (- (.getTime ^java.util.Date now)
                     (.getTime ^java.util.Date last-at))
          age-days (max 0.0 (/ age-ms 86400000.0))]
      (max 0.0 (min 1.0 (- 1.0 (/ age-days (double half-life-days))))))))

;; ------------------------------------------------------------------
;; Async drain worker spawn — shared by post-tool-use and stop
;; ------------------------------------------------------------------

(defn- worker-already-running?
  "Cheap check used by the hook path. Returns true when a lock file
   exists AND its mtime is within the stale window, meaning some
   other drain worker is alive and draining the queue for us."
  [project-root stale-seconds]
  (let [path (paths/jobs-worker-lock project-root)
        f    (io/file path)]
    (and (.exists f)
         (not (locks/stale-at? path stale-seconds)))))

(defn ensure-worker-running!
  "Spawn `succession worker drain` as a detached subprocess unless
   a live worker is already holding the lock. Returns the babashka
   process record on spawn, nil if we skipped.

   Never throws — a failed spawn leaves the job on disk, which the
   next hook invocation will re-try to drain."
  [project-root config]
  (let [stale-secs (or (get-in config [:worker/async :stale-lock-seconds]) 60)]
    (when-not (worker-already-running? project-root stale-secs)
      (try
        (process/process
          {:dir      project-root
           :out      (paths/jobs-worker-log project-root)
           :err      (paths/jobs-worker-log project-root)
           :shutdown nil}
          "succession" "worker" "drain")
        (catch Throwable _ nil)))))

(defn enqueue-and-ensure-worker!
  "Atomic two-step: append a job to the filesystem queue for
   `project-root`, then make sure a drain worker is alive. The hook
   is free to return immediately after this — the worker will pick
   the job up within one scan cycle.

   `job-fields` is the minimal map passed to `store-jobs/make-job`,
   typically `{:type :judge :session ... :payload ...}`. We attach
   `:project-root` here so the caller doesn't have to remember."
  [project-root config job-fields]
  (try
    (let [job (store-jobs/make-job (assoc job-fields :project-root project-root))]
      (store-jobs/enqueue! project-root job)
      (ensure-worker-running! project-root config)
      job)
    (catch Throwable _ nil)))

(defn score-cards
  "Build the `[{:card :weight :recency-fraction}]` shape that salience
   and consult expect. Reads all observations off disk once and groups
   by card id for O(cards) folding.

   Cards with no observations still appear — they get weight 0 and
   recency 0, which keeps tier-baseline scoring non-empty so a
   freshly-created card isn't invisible until its first observation
   lands."
  [project-root cards config now]
  (let [by-card   (store-obs/observations-by-card
                    (store-obs/load-all-observations project-root))
        half-life (or (:weight/decay-half-life-days config) 180)]
    (mapv (fn [c]
            (let [obs (get by-card (:card/id c) [])
                  r   (rollup/rollup-by-session obs)]
              {:card              c
               :weight            (weight/compute r now config)
               :recency-fraction  (recency-fraction r now half-life)}))
          cards)))
