#!/usr/bin/env bb
(ns succession.hooks.post-tool-use
  "PostToolUse hook: LLM judge conscience layer.

   Sonnet is the floor model. Cost control = sampling rate + tool filter
   + session budget, not model downgrade. Haiku is explicitly disallowed.

   Modes:
     off   — no-op
     sync  — block for the judge call (3-6 s). For Phase-2 correctness.
     async — spawn a detached bb subprocess, exit <50 ms. For Phase-3.

   The hook always calls require-not-judge-subprocess! first so a
   subprocess spawned with SUCCESSION_JUDGE_SUBPROCESS=1 can never
   recursively re-enter itself."
  (:require [cheshire.core :as json]
            [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]
            [succession.hooks.common :as common]
            [succession.judge :as judge]
            [succession.reinject :as reinject]
            [succession.refresh :as refresh]
            [succession.config :as config]))

(defn- turn-count-for [session-id]
  (let [turn-file (str "/tmp/.succession-turns-" session-id)]
    (if (fs/exists? turn-file)
      (try (parse-long (str/trim (slurp turn-file))) (catch Exception _ 0))
      0)))

(defn- read-digest [cwd]
  (let [f (str cwd "/.succession/compiled/active-rules-digest.md")]
    (if (fs/exists? f)
      (slurp f)
      "")))

(defn- should-sample?
  "Sampling gate: probability p that this tool use is judged."
  [rate]
  (< (rand) (double (or rate 0.5))))

(defn- cold-start-skipped?
  "Skip judging until we've seen cold-start-skip tool uses in this session."
  [session-id cold-start-skip]
  (let [f (str "/tmp/.succession-tool-count-" session-id)
        cur (if (fs/exists? f)
              (try (parse-long (str/trim (slurp f))) (catch Exception _ 0))
              0)
        nxt (inc cur)]
    (spit f (str nxt))
    (< nxt (or cold-start-skip 5))))

(defn- matches-tool-filter? [tools tool-name]
  (contains? (set tools) tool-name))

(defn- do-judge-sync!
  "Run the judge synchronously and append its verdict to judge.jsonl.
   Also updates the session budget counter."
  [{:keys [cwd session_id tool_name tool_input tool_response] :as _input} config]
  (let [ctx {:tool-name tool_name
             :tool-input tool_input
             :tool-response tool_response
             :active-rules-digest (read-digest cwd)}
        verdict (judge/judge-tool-use ctx config)]
    (when verdict
      (let [entry (-> verdict
                      (assoc :kind "tool"
                             :session session_id
                             :tool tool_name
                             :ts (str (java.time.Instant/now))))]
        (judge/append-log! cwd entry)
        (judge/add-session-budget! session_id (:cost_usd verdict 0.0))))))

(defn- do-judge-async!
  "Fork a detached bb subprocess that runs the sync judge path. Returns
   immediately so the main hook exits well under 50 ms. The child
   inherits SUCCESSION_JUDGE_SUBPROCESS=1, which makes every Succession
   hook it might otherwise trigger no-op."
  [input-json]
  (let [bb-src (str (System/getProperty "user.dir") "/bb/src")
        ;; The child re-parses the same input and runs sync mode.
        child-expr (str
                    "(require 'succession.hooks.post-tool-use) "
                    "(binding [*in* (java.io.StringReader. "
                    (pr-str input-json)
                    ")] "
                    "(succession.hooks.post-tool-use/run-sync-from-stdin!))")
        env (into {} (System/getenv))
        env (assoc env "SUCCESSION_JUDGE_SUBPROCESS" "1")]
    (try
      (process/process {:in nil
                        :out "/tmp/.succession-judge-async.log"
                        :err "/tmp/.succession-judge-async.log"
                        :extra-env env
                        :shutdown nil}
                       "bb" "-cp" bb-src "-e" child-expr)
      (catch Exception _ nil))))

(defn run-sync-from-stdin!
  "Entry point the async child process uses. Reads the same JSON shape
   the parent got and runs the sync judge path. Intentionally never
   writes additionalContext — the parent already returned to Claude
   Code long before this finishes."
  []
  (try
    (let [input (json/parse-string (slurp *in*) true)
          config (config/load-config)]
      (do-judge-sync! input config))
    (catch Exception _ nil)))

(defn- compute-reinject-bundle
  "Run the reinject gate. Returns the bundle string if the gate fires and
   the bundle is non-empty, else nil."
  [cwd session_id transcript_path config turn-count]
  (let [reinject-cfg (:reinject config {})
        byte-threshold (:byteThreshold reinject-cfg 204800)
        turn-threshold (:turnThreshold reinject-cfg 10)]
    (when (reinject/should-reinject? session_id transcript_path
                                     turn-count byte-threshold turn-threshold)
      (let [bundle (reinject/build-reinject-context cwd reinject-cfg)]
        (when (seq bundle) bundle)))))

(defn- emit-additional-context
  "Run the reinject gate AND the refresh gate, combine any outputs into a
   single additionalContext string, and emit one JSON blob on stdout.

   Reinject = heavy byte/turn-gated bundle (1–3 fires/session).
   Refresh  = light tool-call-count-gated reminder (many fires/session).

   Both land adjacent to the most recent tool_result via
   `reorderAttachmentsForAPI`. See succession.refresh for the attention-
   drift experiment that motivates the refresh layer."
  [input config turn-count]
  (let [{:keys [cwd session_id transcript_path]} input
        reinject-bundle (compute-reinject-bundle cwd session_id transcript_path
                                                 config turn-count)
        refresh-text    (refresh/maybe-emit! input (:refresh config {}))
        combined (->> [reinject-bundle refresh-text]
                      (remove nil?)
                      (remove empty?)
                      (clojure.string/join "\n\n"))]
    (when (seq combined)
      (println (json/generate-string
                {:hookSpecificOutput
                 {:hookEventName "PostToolUse"
                  :additionalContext combined}})))))

(defn -main []
  (common/require-not-judge-subprocess!)
  (try
    (let [raw (slurp *in*)
          input (json/parse-string raw true)
          {:keys [cwd session_id tool_name transcript_path]} input
          config (config/load-config)
          judge-cfg (:judge config {})
          enabled? (:enabled judge-cfg false)
          mode (:mode judge-cfg "async")
          tools (:tools judge-cfg ["Bash" "Edit" "Write" "MultiEdit" "Task"])
          sampling (:samplingRate judge-cfg 0.5)
          cold-skip (:coldStartSkip judge-cfg 5)
          budget-ok? (not (judge/budget-exceeded? session_id config))
          turn-count (turn-count-for session_id)]

      (when (and enabled?
                 (not= "off" mode)
                 budget-ok?
                 (matches-tool-filter? tools tool_name)
                 (should-sample? sampling)
                 (not (cold-start-skipped? session_id cold-skip)))
        (case mode
          "sync" (do-judge-sync! input config)
          "async" (do-judge-async! raw)
          nil))

      (when (and (not budget-ok?) enabled?)
        (try
          (judge/append-log! cwd {:kind "budget_exceeded"
                                  :session session_id
                                  :ts (str (java.time.Instant/now))})
          (catch Exception _)))

      (emit-additional-context input config turn-count))
    (catch Exception e
      (binding [*out* *err*]
        (println (str "Succession post_tool_use hook error: " (.getMessage e))))
      (System/exit 0))))

(when (= *file* (System/getProperty "babashka.file"))
  (-main))
