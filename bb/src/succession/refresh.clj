(ns succession.refresh
  "Attention-drift refresh layer — short, tool-call-count-gated reminders
   emitted from PostToolUse as `hookSpecificOutput.additionalContext`.

   This is a separate concern from `reinject`:

     reinject  — heavy bundle (advisory-summary + digest + retrospectives),
                 byte/turn-gated, typically fires 1–3 times per session.
                 Intended as a full context rebuild near Stop or at large
                 byte-growth milestones.

     refresh   — short reminder (one rule body, a few hundred bytes),
                 tool-call-count-gated, fires many times per session.
                 Intended to land adjacent to the now-frame via PostToolUse
                 so the rule stays salient through the 'integration gap'
                 where the model has read the rule but not yet committed
                 to the target behavior.

   Confirmed directionally on pytest-dev__pytest-5103 (experiment 08/04):
   0 productive `replsh eval` calls with CLAUDE.md alone → 18 with
   CLAUDE.md + PostToolUse refresh. See:
   `experiments/08-succession-bench/04-conscience-loop/results/REPORT-attention-drift.md`.

   State layout — /tmp/.succession-refresh-state-<session-id>, JSON:
     {:calls N           ; matched tool calls seen this session
      :emits N           ; emissions so far this session
      :last-emit-call N  ; matched-call index of last emission
      :last-emit-bytes N}; transcript bytes at last emission

   The gate is deliberately independent of the reinject state so the two
   layers don't interfere with each other."
  (:require [cheshire.core :as json]
            [babashka.fs :as fs]
            [clojure.string :as str]))

(def ^:private default-max-emissions
  "Hard ceiling on refresh emissions per session. nil = unbounded.
   The attention-drift experiment found that a cap of 5 was overly
   conservative: integration happened exactly at emission #5 and
   sustainment continued 17 matched calls past the cap with zero
   further reinforcement needed. But on instances where integration
   happens later in the session, an early cap would leave the model
   unreinforced through the friction zone. Default: unbounded. Set
   via `refresh.maxEmissions` in the config if a ceiling is desired."
  nil)

(defn- state-file [session-id]
  (str "/tmp/.succession-refresh-state-" session-id))

(defn read-state [session-id]
  (let [f (state-file session-id)]
    (if (fs/exists? f)
      (try (json/parse-string (slurp f) true)
           (catch Exception _
             {:calls 0 :emits 0 :last-emit-call 0 :last-emit-bytes 0}))
      {:calls 0 :emits 0 :last-emit-call 0 :last-emit-bytes 0})))

(defn- write-state! [session-id state]
  (spit (state-file session-id) (json/generate-string state)))

(defn- transcript-bytes [transcript-path]
  (if (and transcript-path (fs/exists? transcript-path))
    (fs/size transcript-path)
    0))

(defn- matches-tool-filter? [tools tool-name]
  (contains? (set tools) tool-name))

(defn- should-emit?
  "Gate decision. First emission fires on the cold-start-skip-th matching
   call. After that, emissions fire every call-interval matched calls OR
   every byte-threshold transcript growth, whichever comes first, subject
   to the max-emissions cap."
  [{:keys [calls emits last-emit-call last-emit-bytes]}
   cur-bytes
   {:keys [callInterval byteThreshold coldStartSkip maxEmissions]
    :or   {callInterval 5
           byteThreshold 40000
           coldStartSkip 5}}]
  (let [under-cap?  (or (nil? maxEmissions)
                        (< (or emits 0) maxEmissions))
        first-emit? (and (zero? (or emits 0))
                         (>= calls coldStartSkip))
        calls-since (- calls (or last-emit-call 0))
        bytes-since (- cur-bytes (or last-emit-bytes 0))
        later-emit? (and (pos? (or emits 0))
                         (or (>= calls-since callInterval)
                             (>= bytes-since byteThreshold)))]
    (and under-cap? (or first-emit? later-emit?))))

(defn- refresh-text-path
  "Where to find the refresh reminder text. Precedence:
     1. config's :textFile override (absolute path)
     2. $cwd/.succession/refresh-text.md (per-project customization)
     3. $cwd/.succession/compiled/refresh-text.md (compiled from rules)
   Returns the first existing path, or nil."
  [cwd {:keys [textFile]}]
  (->> [textFile
        (when cwd (str cwd "/.succession/refresh-text.md"))
        (when cwd (str cwd "/.succession/compiled/refresh-text.md"))]
       (remove nil?)
       (filter fs/exists?)
       first))

(defn load-refresh-text
  "Load the refresh text body. Returns a non-empty trimmed string, or nil
   if no source is available and no inline :text is configured."
  [cwd refresh-cfg]
  (let [inline (:text refresh-cfg)]
    (cond
      (and (string? inline) (not (str/blank? inline)))
      (str/trim inline)

      :else
      (when-let [p (refresh-text-path cwd refresh-cfg)]
        (let [s (slurp p)]
          (when-not (str/blank? s)
            (str/trim s)))))))

(defn maybe-emit!
  "Main entry point. Called from PostToolUse with the hook input and the
   refresh config. Returns a string to emit as additionalContext, or nil
   if the gate does not fire or no refresh text is available.

   Side effect: updates the per-session state file regardless of whether
   this call results in an emission (to track the tool-call counter)."
  [{:keys [session_id cwd tool_name transcript_path]}
   {:keys [enabled tools] :as refresh-cfg
    :or {tools ["Bash" "Edit" "Write" "MultiEdit"]}}]
  (when (and enabled session_id (matches-tool-filter? tools tool_name))
    (let [state       (read-state session_id)
          calls'      (inc (or (:calls state) 0))
          cur-bytes   (transcript-bytes transcript_path)
          state'      (assoc state :calls calls')]
      (if (should-emit? state' cur-bytes refresh-cfg)
        (if-let [text (load-refresh-text cwd refresh-cfg)]
          (do
            (write-state! session_id
                          (assoc state'
                                 :emits (inc (or (:emits state') 0))
                                 :last-emit-call calls'
                                 :last-emit-bytes cur-bytes))
            text)
          (do
            (write-state! session_id state')
            nil))
        (do
          (write-state! session_id state')
          nil)))))
