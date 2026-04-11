(ns succession.config
  "Central Succession config schema and loader.

   Precedence: ~/.succession/config.json > defaults.

   Haiku is explicitly disallowed as the judge model — Succession treats
   Sonnet as the floor. Attempting to set judge.model = haiku raises an
   exception at load time rather than silently downgrading."
  (:require [cheshire.core :as json]
            [babashka.fs :as fs]))

(def model-ids
  {"sonnet" "claude-sonnet-4-6"
   "opus"   "claude-opus-4-6"})

(def defaults
  {;; Stop hook (existing)
   :model "sonnet"
   :correctionModel "sonnet"
   :extractEveryKTokens 80000
   :debug false

   ;; Reinject — hybrid bytes-or-turns gate (heavy bundle, fires ~1–3/session)
   :reinject {:byteThreshold 204800
              :turnThreshold 10
              :includeJudgeRetrospectives true
              :maxRetrospectives 5}

   ;; Refresh — attention-drift reminder layer (short text, fires many/session).
   ;; Confirmed directionally on pytest-5103 (exp 08/04): CLAUDE.md alone → 0
   ;; productive replsh evals, CLAUDE.md + PostToolUse refresh → 18. Emits a
   ;; short reminder adjacent to the now-frame via PostToolUse additionalContext.
   ;; Text source precedence (see succession.refresh/refresh-text-path):
   ;;   1. :text inline string
   ;;   2. :textFile absolute path
   ;;   3. $cwd/.succession/refresh-text.md
   ;;   4. $cwd/.succession/compiled/refresh-text.md
   ;; Disabled by default; enable per-project by writing a refresh-text.md.
   :refresh {:enabled false
             :tools ["Bash" "Edit" "Write" "MultiEdit"]
             :callInterval 5
             :byteThreshold 40000
             :coldStartSkip 5
             :maxEmissions nil  ; nil = unbounded; set to a number to cap
             :text nil          ; inline override
             :textFile nil}     ; absolute path override

   ;; Judge — conscience layer
   :judge {:enabled false
           :mode "async"          ; off | sync | async
           :tools ["Bash" "Edit" "Write" "MultiEdit" "Task"]
           :samplingRate 0.5
           :model "sonnet"
           :escalationModel "opus"
           :coldStartSkip 5
           :sessionBudgetUsd 0.50}})

(defn- deep-merge
  "Recursive map merge — keeps defaults for any key not set in the override."
  [a b]
  (cond
    (and (map? a) (map? b)) (merge-with deep-merge a b)
    (some? b) b
    :else a))

(defn- validate!
  "Raise on known-bad config values."
  [cfg]
  (let [judge-model (get-in cfg [:judge :model])]
    (when (= "haiku" judge-model)
      (throw (ex-info "Succession: judge.model=\"haiku\" is not allowed. Use sonnet or opus."
                      {:judge-model judge-model}))))
  cfg)

(defn load-config
  "Load merged config. Optionally pass a path override; defaults to
   ~/.succession/config.json."
  ([] (load-config nil))
  ([override-path]
   (let [path (or override-path
                  (str (System/getProperty "user.home") "/.succession/config.json"))
         user-cfg (when (fs/exists? path)
                    (try (json/parse-string (slurp path) true)
                         (catch Exception _ {})))]
     (validate! (deep-merge defaults (or user-cfg {}))))))

(defn resolve-model
  "Map a symbolic model name (sonnet/opus) to the concrete ID used by the
   claude CLI. Falls back to the input string if unknown."
  [name]
  (get model-ids name name))
