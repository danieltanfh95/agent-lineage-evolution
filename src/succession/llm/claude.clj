(ns succession.llm.claude
  "Single canonical `call` function for every `claude -p` invocation in
   the identity system. Judge, extract, reconcile, and consult all route
   through here so there is exactly one place that:

     - chooses a model id
     - sets timeouts
     - sets SUCCESSION_JUDGE_SUBPROCESS=1 so the spawned Claude does
       not re-trigger our own hooks (infinite recursion would be fatal)
     - parses the JSON `--output-format json` envelope into the raw
       assistant text
     - reports cost and latency

   Sonnet is the floor — never Haiku. Opus is the escalation. Model
   ids come from config; this namespace only knows the call shape.

   Reference: `.plans/succession-identity-cycle.md` §Config (:judge/llm,
   :reconcile/llm, :consult/llm)."
  (:require [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.string :as str]))

(defn- strip-fences
  "Defensively strip markdown fencing that models sometimes wrap JSON in."
  [s]
  (when s
    (-> s
        (str/replace #"(?m)^```json?\s*$" "")
        (str/replace #"(?m)^```\s*$" "")
        str/trim)))

(defn- cost-estimate
  "Rough USD cost for a single call. Sonnet-4.6 (Mar 2026 pricing):
   $3/M in, $15/M out. Opus-4.6: $15/M in, $75/M out."
  [model-id in-toks out-toks]
  (let [[in-rate out-rate] (cond
                             (str/includes? (or model-id "") "opus")   [15.0 75.0]
                             (str/includes? (or model-id "") "haiku")  [1.0  5.0]
                             :else                                     [3.0  15.0])]
    (+ (* (/ in-toks  1.0e6) in-rate)
       (* (/ out-toks 1.0e6) out-rate))))

(defn call
  "Invoke `claude -p` synchronously with `prompt` on stdin.

   Opts:
     :model-id       - required. Full Claude model id (e.g. 'claude-sonnet-4-6').
     :timeout-secs   - required. Hard ceiling on the subprocess.
     :input-toks     - rough token count for cost estimation. Default 400.
     :output-toks    - rough token count for cost estimation. Default 120.

   Returns `{:ok? bool :text str :raw-output str :latency-ms n :cost-usd f :model-id str :exit int}`.
   Never throws on subprocess failure — exceptions become `{:ok? false :error ...}`."
  [prompt {:keys [model-id timeout-secs input-toks output-toks]
           :or {input-toks 400 output-toks 120}}]
  (let [t0 (System/currentTimeMillis)]
    (try
      (let [env (-> (into {} (System/getenv))
                    (assoc "SUCCESSION_JUDGE_SUBPROCESS" "1"))
            result (process/shell
                     {:in       prompt
                      :out      :string
                      :err      :string
                      :timeout  (* timeout-secs 1000)
                      :extra-env env}
                     "claude" "-p" "--model" model-id "--output-format" "json")
            latency (- (System/currentTimeMillis) t0)]
        (if (zero? (:exit result))
          (let [parsed (json/parse-string (:out result) true)
                text   (if (sequential? parsed)
                         (:result (last parsed))
                         (or (:result parsed) (str parsed)))]
            {:ok?            true
             :text           (strip-fences text)
             :raw-output     (:out result)
             :latency-ms     latency
             :cost-usd       (cost-estimate model-id input-toks output-toks)
             :input-tokens   input-toks
             :output-tokens  output-toks
             :model-id       model-id
             :exit           0})
          {:ok?        false
           :error      (:err result)
           :raw-output (:out result)
           :latency-ms latency
           :exit       (:exit result)
           :model-id   model-id}))
      (catch Throwable t
        {:ok?        false
         :error      (.getMessage t)
         :latency-ms (- (System/currentTimeMillis) t0)
         :model-id   model-id}))))

(defn parse-json
  "Parse the text of a successful call into a Clojure data structure.
   Returns nil on parse failure. Accepts both maps and sequences."
  [text]
  (when text
    (try (json/parse-string text true)
         (catch Throwable _ nil))))
