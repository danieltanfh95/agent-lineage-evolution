(ns succession.cli.config-validate
  "`succession config <subcmd>` — config tools.

   Subcommands:

     validate   - read .succession/config.edn (if present), deep-merge
                  with defaults, and report problems found by
                  `config/validate`.
     show       - pretty-print the effective config (defaults + overlay).
     init       - write a starter .succession/config.edn if none exists.
                  Idempotent — will refuse to overwrite an existing file.

   Reference: `.plans/succession-identity-cycle.md` §Config."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [succession.config :as config]
            [succession.store.paths :as paths]))

(def ^:private template-config
  "Starter config written by `config init`. Comments explain each
   tunable. Everything here is the default — callers only need to
   change the values they disagree with."
  (str
    ";; .succession/config.edn — Succession identity-cycle config\n"
    ";;\n"
    ";; Every tunable in the system lives here. Values are deep-merged\n"
    ";; over the defaults in `succession.config/default-config`\n"
    ";; so you only need to override the ones you disagree with.\n"
    ";;\n"
    ";; Reference: `.plans/succession-identity-cycle.md` §Config.\n"
    "\n"
    "{:succession/config-version 1\n"
    "\n"
    " ;; --- Weight formula ---\n"
    " ;; freq = min(sqrt(distinct-sessions), freq-cap)\n"
    " ;; span = (1 + log(1+span_days))^span-exponent\n"
    " ;; gap  = 1 + gap-crossings\n"
    " ;; decay = 0.5 ^ (days_since_last / half-life)\n"
    " :weight/freq-cap                4.0\n"
    " :weight/span-exponent           1.5\n"
    " :weight/within-session-penalty  0.5\n"
    " :weight/decay-half-life-days    180\n"
    " :weight/violation-penalty-rate  0.5\n"
    " :weight/gap-threshold-sessions  1\n"
    "\n"
    " ;; --- Tier rules with hysteresis ---\n"
    " ;; enter: ALL conditions must hold; exit: ANY condition triggers.\n"
    " :tier/rules\n"
    " {:principle {:enter {:min-weight 30.0 :max-violation-rate 0.0 :min-gap-crossings 5}\n"
    "              :exit  {:max-weight 20.0 :min-violation-rate 0.1}}\n"
    "  :rule      {:enter {:min-weight 5.0  :max-violation-rate 0.3 :min-gap-crossings 1}\n"
    "              :exit  {:max-weight 3.0  :min-violation-rate 0.5}}\n"
    "  :ethic     {:enter {}                 ; default landing\n"
    "              :exit  {:archive-below-weight 0.5}}}\n"
    "\n"
    " ;; --- Salience (PreToolUse / PostToolUse refresh ranking) ---\n"
    " :salience/profile\n"
    " {:feature-weights {:tier-weight 3.0 :tag-match 2.0 :fingerprint 4.0\n"
    "                    :recency 0.5 :weight 1.0}\n"
    "  :top-k    12}\n"
    "\n"
    " ;; --- Refresh gate (pacing filters, not budgets — see infinite-context principle) ---\n"
    " :refresh/gate\n"
    " {:integration-gap-turns 2\n"
    "  :byte-threshold        200\n"
    "  :cold-start-skip-turns 1}\n"
    "\n"
    " ;; --- Consult advisory reminder ---\n"
    " :consult/advisory\n"
    " {:every-n-turns              8\n"
    "  :on-contradiction-adjacency true}\n"
    "\n"
    " ;; --- LLM models (deepseek primary, Sonnet fallback/escalation) ---\n"
    " :judge/llm     {:model \"deepseek/deepseek-chat\"\n"
    "                 :fallback-model \"claude-sonnet-4-6\"\n"
    "                 :timeout-seconds 30}\n"
    " :reconcile/llm {:model \"claude-sonnet-4-6\" :timeout-seconds 60\n"
    "                 :auto-apply-confidence 0.8}\n"
    " :consult/llm   {:model \"claude-sonnet-4-6\" :timeout-seconds 60}\n"
    "\n"
    " ;; --- Escalation thresholds ---\n"
    " :escalation/sustained-violation {:min-rate 0.1 :min-sessions 3}\n"
    " :escalation/drift-alarm         {:contradictions-per-n-tool-calls 3\n"
    "                                  :n-tool-calls 20}\n"
    "\n"
    " ;; --- Whitepaper §3.3.3 knowledge categories ---\n"
    " :card/categories [:strategy :failure-inheritance :relational-calibration :meta-cognition]}\n"))

(defn- check-hook-paths [project-root]
  (let [settings-file (str project-root "/.claude/settings.local.json")]
    (when (.exists (io/file settings-file))
      (let [settings (json/parse-string (slurp settings-file) true)
            hooks    (mapcat val (:hooks settings))
            commands (mapcat #(or (:commands %) []) hooks)
            cp-args  (keep #(second (re-find #"-cp\s+(\S+)" %)) commands)]
        (doseq [path cp-args]
          (if (.exists (io/file path))
            (println (format "  [ok] hook classpath   %s" path))
            (println (format "  [WARN] hook classpath missing: %s" path))))))))

(defn validate!
  "Validate the effective config for `project-root`. Returns 0 on
   success, 1 on validation errors, and prints a report."
  [project-root]
  (let [cfg (config/load-config project-root)
        problems (config/validate cfg)]
    (if (empty? problems)
      (do (println "config valid: " (str project-root "/.succession/config.edn"))
          (println "effective values merged over defaults.")
          (check-hook-paths project-root)
          0)
      (do (println "config INVALID:" (str project-root "/.succession/config.edn"))
          (doseq [p problems]
            (println (format "  - %s: %s"
                             (pr-str (:path p)) (:problem p))))
          (check-hook-paths project-root)
          1))))

(defn show!
  "Pretty-print the effective config."
  [project-root]
  (let [cfg (config/load-config project-root)]
    (pprint/pprint cfg)
    0))

(defn init!
  "Write a starter config file. Refuses to overwrite an existing one."
  [project-root]
  (let [dir  (paths/root project-root)
        path (str dir "/config.edn")
        f    (io/file path)]
    (paths/ensure-dir! dir)
    (if (.exists f)
      (do (binding [*out* *err*]
            (println "config already exists:" path)
            (println "refusing to overwrite — edit directly or delete first."))
          1)
      (do (spit path template-config)
          (println "wrote starter config:" path)
          0))))

(defn run
  "Dispatch entry called from `core/-main`."
  [project-root args]
  (let [sub (first args)
        rc  (case sub
              "validate" (validate! project-root)
              "show"     (show! project-root)
              "init"     (init! project-root)
              (do (binding [*out* *err*]
                    (println "usage: succession config <validate|show|init>")
                    (when sub (println "unknown subcommand:" sub)))
                  2))]
    (System/exit (or rc 0))))
