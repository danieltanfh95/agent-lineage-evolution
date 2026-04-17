(ns succession.cli.bench-common
  "Shared utilities for all bench runners (judge, reconcile, consult).

   Extracted from bench.clj to eliminate duplication per the
   layer-scoped common namespace convention (clojure-style.md SS3).
   Provides model lists, scoring, output formatting, arg parsing,
   and the reusable runner loop."
  (:require [clojure.string :as str]
            [succession.store.paths :as paths]))

;; ------------------------------------------------------------------
;; 1. Model lists
;; ------------------------------------------------------------------

(def default-models
  "Curated default sweep — 6 models covering the cost/quality spectrum."
  ["deepseek/deepseek-chat"
   "claude-sonnet-4-6"
   "opencode-go/mimo-v2-pro"
   "opencode-go/glm-5.1"
   "openai/gpt-5.4-mini"
   "openai/gpt-5.4"])

(def all-models
  "Full sweep — default 6 + 5 additional local/Chinese models."
  (into default-models
        ["opencode-go/kimi-k2.5"
         "opencode-go/qwen3.6-plus"
         "opencode-go/minimax-m2.7"
         "openrouter/qwen/qwen3-235b-a22b"
         "openrouter/google/gemma-4-31b-it"]))

;; ------------------------------------------------------------------
;; 2. Scoring — pure functions
;; ------------------------------------------------------------------

(defn compute-grade
  "Weighted composite grade: 40% accuracy + 15% parse + 25% cost-efficiency
   + 20% latency-efficiency. A >= 85, B >= 70, C >= 55, D >= 40, F < 40."
  [parse-pct accuracy-pct avg-cost avg-latency]
  (let [cost-eff    (max 0.0 (min 100.0 (- 100.0 (* 2000.0 avg-cost))))
        latency-eff (max 0.0 (min 100.0 (- 100.0 (* 0.002 avg-latency))))
        composite   (+ (* 0.40 accuracy-pct)
                       (* 0.15 parse-pct)
                       (* 0.25 cost-eff)
                       (* 0.20 latency-eff))]
    (cond
      (>= composite 85.0) "A"
      (>= composite 70.0) "B"
      (>= composite 55.0) "C"
      (>= composite 40.0) "D"
      :else                "F")))

(defn aggregate-model
  "Aggregate per-case scores into model-level metrics. If any case has
   a :judge-score, computes :avg-judge-score on the aggregate."
  [model-id case-scores]
  (let [total        (count case-scores)
        parsed       (count (filter :parsed? case-scores))
        correct      (count (filter :correct? case-scores))
        costs        (map :cost-usd case-scores)
        latencies    (map :latency-ms case-scores)
        in-toks      (map :input-tokens case-scores)
        out-toks     (map :output-tokens case-scores)
        judge-scores (keep :judge-score case-scores)
        parse-pct    (if (pos? total) (* 100.0 (/ parsed total)) 0.0)
        accuracy-pct (if (pos? parsed) (* 100.0 (/ correct parsed)) 0.0)
        avg-cost     (if (pos? total) (/ (reduce + 0.0 costs) total) 0.0)
        avg-latency  (if (pos? total) (/ (reduce + 0.0 latencies) total) 0.0)
        avg-in-toks  (if (pos? total) (/ (reduce + 0 in-toks) total) 0)
        avg-out-toks (if (pos? total) (/ (reduce + 0 out-toks) total) 0)
        avg-judge    (when (seq judge-scores)
                       (/ (reduce + 0.0 judge-scores) (count judge-scores)))]
    (cond-> {:model-id       model-id
             :total          total
             :parsed         parsed
             :correct        correct
             :parse-pct      parse-pct
             :accuracy-pct   accuracy-pct
             :avg-cost       avg-cost
             :avg-latency    avg-latency
             :avg-in-tokens  avg-in-toks
             :avg-out-tokens avg-out-toks
             :grade          (compute-grade parse-pct accuracy-pct avg-cost avg-latency)
             :cases          case-scores}
      avg-judge (assoc :avg-judge-score avg-judge))))

;; ------------------------------------------------------------------
;; 3. Formatting helpers
;; ------------------------------------------------------------------

(defn fmt-cost [c] (format "$%.4f" (double c)))
(defn fmt-latency [ms] (format "%.1fs" (/ (double ms) 1000.0)))
(defn fmt-pct [p] (format "%.0f%%" (double p)))
(defn fmt-tokens [n] (if (pos? n) (str (long n)) "-"))

;; ------------------------------------------------------------------
;; 4. Output — markdown table + EDN persistence
;; ------------------------------------------------------------------

(defn print-results-table
  "Print a markdown comparison table to stdout. When any model result
   contains :avg-judge-score, includes a Judge column."
  [model-results]
  (let [has-judge? (some :avg-judge-score model-results)]
    (println)
    (if has-judge?
      (do
        (println "| Model | Parse% | Accuracy% | Avg Cost | Avg Latency | Avg Out Toks | Judge | Grade |")
        (println "|-------|--------|-----------|----------|-------------|--------------|-------|-------|"))
      (do
        (println "| Model | Parse% | Accuracy% | Avg Cost | Avg Latency | Avg Out Toks | Grade |")
        (println "|-------|--------|-----------|----------|-------------|--------------|-------|")))
    (doseq [r (sort-by :accuracy-pct > model-results)]
      (if has-judge?
        (println (format "| %s | %s | %s | %s | %s | %s | %s | %s |"
                         (:model-id r)
                         (fmt-pct (:parse-pct r))
                         (fmt-pct (:accuracy-pct r))
                         (fmt-cost (:avg-cost r))
                         (fmt-latency (:avg-latency r))
                         (fmt-tokens (:avg-out-tokens r 0))
                         (if-let [j (:avg-judge-score r)]
                           (format "%.1f" (double j))
                           "-")
                         (:grade r)))
        (println (format "| %s | %s | %s | %s | %s | %s | %s |"
                         (:model-id r)
                         (fmt-pct (:parse-pct r))
                         (fmt-pct (:accuracy-pct r))
                         (fmt-cost (:avg-cost r))
                         (fmt-latency (:avg-latency r))
                         (fmt-tokens (:avg-out-tokens r 0))
                         (:grade r)))))))

(defn write-bench-results!
  "Write detailed EDN to .succession/bench/<timestamp>.edn."
  [project-root model-results bench-kind]
  (let [bench-dir (str (paths/root project-root) "/bench")
        ts        (.format (java.text.SimpleDateFormat. "yyyyMMdd-HHmmss") (java.util.Date.))
        out-file  (str bench-dir "/" ts ".edn")]
    (paths/ensure-dir! bench-dir)
    (spit out-file (pr-str {:bench/timestamp ts
                            :bench/kind      bench-kind
                            :bench/results   model-results}))
    (println (str "\nDetailed results written to: " out-file))))

;; ------------------------------------------------------------------
;; 5. CLI — parse-args
;; ------------------------------------------------------------------

(defn parse-args
  "Parse bench CLI args. `bench-name` is used in help output
   (e.g. \"reconcile\", \"consult\", or \"\" for the default judge bench)."
  [args bench-name]
  (loop [args (seq args)
         opts {:models default-models :runs 1 :timeout 45 :output-dir nil}]
    (if-not args
      opts
      (let [[a & rest] args]
        (case a
          "--models"     (let [v (first rest)]
                          (recur (next rest)
                                 (assoc opts :models
                                        (if (= "all" v)
                                          all-models
                                          (str/split v #",")))))
          "--runs"       (recur (next rest) (assoc opts :runs (parse-long (first rest))))
          "--timeout"    (recur (next rest) (assoc opts :timeout (parse-long (first rest))))
          "--output-dir" (recur (next rest) (assoc opts :output-dir (first rest)))
          "--with-judge" (recur rest (assoc opts :with-judge? true))
          ("--help" "-h")
          (do (println (str "Usage: succession bench "
                            (when (seq bench-name) (str bench-name " "))
                            "[options]"))
              (println "  --models m1,m2,...   Comma-separated model IDs (default: curated 6)")
              (println "  --models all         Full 11-model sweep")
              (println "  --runs N             Runs per case per model (default: 1)")
              (println "  --timeout N          Per-call timeout in seconds (default: 45)")
              (println "  --output-dir PATH    EDN output directory (default: .succession/bench/)")
              (when (= bench-name "consult")
                (println "  --with-judge         Enable Sonnet LLM judge scoring (adds cost)"))
              (System/exit 0))
          ;; Unknown flag — skip
          (recur rest opts))))))

;; ------------------------------------------------------------------
;; 6. Runner — reusable model loop
;; ------------------------------------------------------------------

(defn run-model-loop
  "Run all fixture cases through a single model. Calls `run-single-fn`
   for each (fixture, model-id, timeout-secs) tuple. If 3 consecutive
   cases have all runs fail to parse, skips the remainder.

   `run-single-fn`: (fn [fixture model-id timeout-secs] -> score-map)
   where score-map must contain at least :parsed? and :correct?."
  [model-id cases run-single-fn {:keys [timeout-secs runs]
                                  :or   {timeout-secs 45 runs 1}}]
  (loop [remaining   cases
         all-raw     []
         consec-fail 0]
    (if (or (empty? remaining) (>= consec-fail 3))
      (do
        (when (>= consec-fail 3)
          (print " [SKIP:timeout]"))
        (let [agg (aggregate-model model-id all-raw)]
          (assoc agg :raw-runs all-raw :runs-per-case runs)))
      (let [fixture     (first remaining)
            run-results (vec (for [run-idx (range runs)]
                              (assoc (run-single-fn fixture model-id timeout-secs)
                                     :run-idx run-idx)))
            any-correct (some :correct? run-results)
            any-parsed  (some :parsed? run-results)
            all-failed  (not any-parsed)]
        (print (if any-correct "." (if all-failed "x" "~")))
        (flush)
        (recur (rest remaining)
               (into all-raw run-results)
               (if all-failed (inc consec-fail) 0))))))
