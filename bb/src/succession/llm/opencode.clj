(ns succession.llm.opencode
  "Transport for `opencode run` — the non-interactive CLI for opencode-go
   models (mimo-v2-pro, mimo-v2-flash, etc.).

   Same `call` return shape as `claude.clj`:
     {:ok? :text :raw-output :latency-ms :cost-usd :model-id :exit
      :credits-exhausted?}

   Key differences from claude -p:
     - Output is NDJSON event stream, not a single JSON envelope
     - Exit code is always 0, even on errors — must parse error events
     - Cost is actual (from step_finish), not estimated
     - 402 credits-exhausted comes as a JSON error event"
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

(defn- parse-event
  "Parse a single NDJSON line. Returns nil on parse failure."
  [line]
  (when-not (str/blank? line)
    (try (json/parse-string line true)
         (catch Throwable _ nil))))

(defn parse-event-stream
  "Parse NDJSON event stream from opencode. Returns
   {:text str :cost num :tokens map :error str :finish-reason str
    :credits-exhausted? bool}."
  [ndjson-str]
  (let [lines  (str/split-lines (or ndjson-str ""))
        events (keep parse-event lines)]
    (reduce
      (fn [acc evt]
        (case (:type evt)
          "text"
          (update acc :text str (get-in evt [:part :text] ""))

          "step_finish"
          (assoc acc
                 :cost          (get-in evt [:part :cost])
                 :tokens        (get-in evt [:part :tokens])
                 :finish-reason (get-in evt [:part :reason]))

          "error"
          (let [msg (or (get-in evt [:error :data :message])
                        (get-in evt [:error :message])
                        (str (:error evt)))]
            (assoc acc
                   :error msg
                   :credits-exhausted?
                   (boolean (or (str/includes? (str msg) "402")
                                (str/includes? (str/lower-case (str msg)) "credits")))))

          ;; ignore other event types (step_start, etc.)
          acc))
      {:text "" :credits-exhausted? false}
      events)))

(defn call
  "Invoke `opencode run` synchronously.
   Returns {:ok? :text :raw-output :latency-ms :cost-usd :model-id
            :credits-exhausted? :exit}."
  [prompt {:keys [model-id timeout-secs]}]
  (let [t0 (System/currentTimeMillis)]
    (try
      (let [env (-> (into {} (System/getenv))
                    (assoc "SUCCESSION_JUDGE_SUBPROCESS" "1"))
            result (process/shell
                     {:in       prompt
                      :out      :string
                      :err      :string
                      :timeout  (* (or timeout-secs 30) 1000)
                      :extra-env env}
                     "opencode" "run" "--model" model-id "--format" "json")
            latency (- (System/currentTimeMillis) t0)
            parsed  (parse-event-stream (:out result))]
        (if (:error parsed)
          {:ok?                false
           :error              (:error parsed)
           :credits-exhausted? (:credits-exhausted? parsed)
           :raw-output         (:out result)
           :latency-ms         latency
           :cost-usd           (or (:cost parsed) 0.0)
           :model-id           model-id
           :exit               (:exit result)}
          {:ok?                true
           :text               (strip-fences (:text parsed))
           :raw-output         (:out result)
           :latency-ms         latency
           :cost-usd           (or (:cost parsed) 0.0)
           :input-tokens       (get-in parsed [:tokens :input] 0)
           :output-tokens      (get-in parsed [:tokens :output] 0)
           :model-id           model-id
           :credits-exhausted? false
           :exit               (:exit result)}))
      (catch Throwable t
        {:ok?                false
         :error              (.getMessage t)
         :credits-exhausted? false
         :latency-ms         (- (System/currentTimeMillis) t0)
         :model-id           model-id}))))
