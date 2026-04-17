(ns succession.config
  "Config loader and defaults for the identity-cycle system.

   Every tunable in the system lives in `.succession/config.edn` at the
   project root. Domain functions never inline magic numbers — they take
   the config map as an argument. This namespace provides:

   - `default-config`: the full default map (the starting values).
   - `load-config`: read and merge with defaults, returning the effective
     config for a single hook/cli invocation.
   - `validate`: schema check — returns `nil` on success, a vector of
     `{:path ... :problem ...}` maps on failure.

   Reference: `.plans/succession-identity-cycle.md` §Config."
  (:require [clojure.edn :as edn]))

(def default-config
  "Baseline config. Every value tunable via `.succession/config.edn`
   which is deep-merged over this map at load time."
  {:succession/config-version 1

   ;; --- Weight formula params ---
   :weight/freq-cap                4.0    ; min(sqrt(freq), cap)
   :weight/span-exponent           1.5    ; (1 + log(1+span_days))^exp
   :weight/within-session-penalty  0.5    ; multiplier when gap-crossings = 0
   :weight/decay-half-life-days    180
   :weight/violation-penalty-rate  0.5    ; base * violation_rate * this
   :weight/gap-threshold-sessions  1      ; sessions since prev = gap crossing

   ;; --- Tier rules with hysteresis (enter/exit bands) ---
   :tier/rules
   {:principle {:enter {:min-weight 30.0
                        :max-violation-rate 0.0
                        :min-gap-crossings 5}
                :exit  {:max-weight 20.0
                        :min-violation-rate 0.1}}
    :rule      {:enter {:min-weight 5.0
                        :max-violation-rate 0.3
                        :min-gap-crossings 1}
                :exit  {:max-weight 3.0
                        :min-violation-rate 0.5}}
    :ethic     {:enter {}     ; default landing
                :exit  {:archive-below-weight 0.5}}}

   ;; --- Salience profile (PostToolUse / PreToolUse ranking) ---
   :salience/profile
   {:feature-weights {:tier-weight  3.0
                      :tag-match    2.0
                      :fingerprint  4.0
                      :recency      0.5
                      :weight       1.0}
    :top-k            12}

   ;; --- Refresh gate (pacing filters, not budgets — see infinite-context principle) ---
   :refresh/gate
   {:integration-gap-turns 2
    :byte-threshold        200
    :cold-start-skip-turns 1}

   ;; --- Consult advisory reminder cadence ---
   :consult/advisory
   {:every-n-turns               8
    :on-contradiction-adjacency  true}

   ;; --- Escalation thresholds ---
   :escalation/sustained-violation
   {:min-rate     0.1
    :min-sessions 3}
   :escalation/drift-alarm
   {:contradictions-per-n-tool-calls 3
    :n-tool-calls                    20}

   ;; --- LLM models ---
   :reconcile/llm
   {:model                 "deepseek/deepseek-chat"
    :auto-apply-confidence 0.8
    :timeout-seconds       90
    :max-batch-size        10}
   :judge/llm
   {:model           "deepseek/deepseek-chat"
    :fallback-model  "claude-sonnet-4-6"
    :timeout-seconds 30
    :context-window  {:n 3 :max-chars 600}}
   :consult/llm
   {:model           "deepseek/deepseek-chat"
    :timeout-seconds 60}

   ;; --- Correction detection (UserPromptSubmit) ---
   ;; Regex patterns are matched against the raw user prompt text.
   ;; A hit writes a :contradicted observation and a :mark-contradiction
   ;; delta. Patterns are intentionally broad — false positives are fine
   ;; because the extract LLM filters downstream.
   :correction/patterns
   ["(?i)\\bno,?\\s+(?:use|do|try)\\b"
    "(?i)\\bstop\\s+\\w+ing\\b"
    "(?i)\\bdon'?t\\s+\\w+"
    "(?i)\\bactually[,.]?\\s"
    "(?i)\\bthat'?s\\s+(?:wrong|not\\s+right)\\b"
    "(?i)\\binstead[,.]?\\s"
    "(?i)\\bnot\\s+that\\b"]

   ;; --- Async drain worker ---
   ;; The post-tool-use and stop hooks enqueue LLM work into
   ;; `staging/jobs/`; a single detached `succession worker drain`
   ;; process pulls jobs off the queue, runs the handlers, and self-
   ;; exits once idle. See async-lane plan §Worker lifecycle.
   :worker/async
   {:idle-timeout-seconds  30   ; exit after this long with no work
    :parallelism           2    ; core.async pipeline-blocking lanes
    :stale-lock-seconds    60   ; lock mtime older than this = dead worker
    :heartbeat-seconds     20   ; lock mtime refresh cadence (3x grace)
    :scan-interval-ms      500  ; jobs-dir poll interval
    :dead-letter-enabled   true}

   ;; --- Worker log verbosity ---
   ;; :debug restores all scanner/tick lines (fires every 500ms when idle).
   ;; :info (default) suppresses debug noise while keeping all meaningful events.
   :worker/log-level :info

   ;; --- Four knowledge categories (whitepaper §3.3.3) ---
   :card/categories [:strategy :failure-inheritance :relational-calibration :meta-cognition]

   ;; --- Friction tiers (card protection) ---
   ;; Values are numeric weights: 0.0 (open) to 1.0 (locked).
   ;; Tier multipliers scale effective friction: principle cards get 2x.
   :friction/tiers {:open 0.0 :soft 0.3 :firm 0.7 :locked 1.0}
   :friction/tier-multipliers {:principle 2.0 :rule 1.0 :ethic 0.5}})

(def valid-tiers
  "Closed set of valid tier keywords. Anything else is a schema error."
  #{:principle :rule :ethic})

(def valid-categories
  "Closed set of whitepaper §3.3.3 knowledge categories."
  #{:strategy :failure-inheritance :relational-calibration :meta-cognition})

(def valid-frictions
  "Closed set of friction tiers for card protection."
  #{:open :soft :firm :locked})

(def valid-observation-kinds
  "Closed set of observation kinds."
  #{:confirmed :violated :invoked :consulted :contradicted})

(defn deep-merge
  "Recursively merge maps. Non-map values in `b` overwrite `a`."
  [a b]
  (cond
    (and (map? a) (map? b)) (merge-with deep-merge a b)
    (some? b)               b
    :else                   a))

(defn load-config
  "Load config from `.succession/config.edn` (if present), merged over
   `default-config`. `root` is the project directory. Returns the
   effective config map."
  [root]
  (let [path (str root "/.succession/config.edn")
        file (java.io.File. path)]
    (if (.exists file)
      (deep-merge default-config (edn/read-string (slurp file)))
      default-config)))

(defn- problem [path msg]
  {:path path :problem msg})

(defn validate
  "Return nil on success, or a vector of {:path ... :problem ...} maps
   on failure. Checks structural invariants but not semantic sanity."
  [config]
  (seq
    (concat
      (keep identity
        [(when-not (number? (:weight/freq-cap config))
           (problem [:weight/freq-cap] "must be a number"))
         (when-not (number? (:weight/span-exponent config))
           (problem [:weight/span-exponent] "must be a number"))
         (when-not (and (number? (:weight/decay-half-life-days config))
                        (pos? (:weight/decay-half-life-days config)))
           (problem [:weight/decay-half-life-days] "must be a positive number"))
         (when-not (every? valid-categories (:card/categories config))
           (problem [:card/categories] "contains unknown category"))
         (when-let [ll (:worker/log-level config)]
           (when-not (contains? #{:debug :info :warn :error} ll)
             (problem [:worker/log-level] "must be one of :debug :info :warn :error")))])
      (when-let [tier-rules (:tier/rules config)]
        (for [tier valid-tiers :when (not (contains? tier-rules tier))]
          (problem [:tier/rules tier] "tier missing from :tier/rules")))
      (when-let [w (:worker/async config)]
        (for [k [:idle-timeout-seconds :parallelism :stale-lock-seconds
                 :heartbeat-seconds :scan-interval-ms]
              :when (not (and (number? (get w k)) (pos? (get w k))))]
          (problem [:worker/async k] "must be a positive number"))))))
