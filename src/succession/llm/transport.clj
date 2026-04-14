(ns succession.llm.transport
  "Thin dispatcher — routes LLM calls by model-id prefix.

   - opencode-go/*, openrouter/*, mimo/*, openai/*, opencode/*,
     google/*, deepseek/*, moonshotai/*, moonshotai-cn/* → opencode/call
   - anything else → claude/call (unchanged)

   Supports :fallback-model-id — on :credits-exhausted?, automatically
   retries with the fallback model (typically a Claude model)."
  (:require [clojure.string :as str]
            [succession.llm.claude :as claude]
            [succession.llm.opencode :as opencode]))

(defn- opencode-model? [model-id]
  (boolean (some #(str/starts-with? (or model-id "") %)
                 ["opencode-go/" "openrouter/" "mimo/" "openai/" "opencode/"
                  "google/" "deepseek/" "moonshotai/" "moonshotai-cn/"])))

(defn- call-impl [prompt opts]
  (if (opencode-model? (:model-id opts))
    (opencode/call prompt opts)
    (claude/call prompt opts)))

(defn call
  "Route to the correct transport based on model-id prefix.
   Supports :fallback-model-id — on :credits-exhausted?, retries
   with the fallback (typically a Claude model)."
  [prompt {:keys [fallback-model-id] :as opts}]
  (let [result (call-impl prompt opts)]
    (if (and (not (:ok? result))
             (:credits-exhausted? result)
             fallback-model-id)
      (call-impl prompt (assoc opts
                               :model-id fallback-model-id
                               :fallback-model-id nil))
      result)))
