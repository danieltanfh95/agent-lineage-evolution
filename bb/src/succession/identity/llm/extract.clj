(ns succession.identity.llm.extract
  "LLM extraction: transcript → candidate cards + observations.

   Runs at Stop (detached) and re-runs at PreCompact. Reads the
   transcript window, asks Sonnet to identify behavioral claims that
   *the agent* would benefit from tracking as identity, and produces
   deltas that the staging log can append.

   Two differences from the old `extract.clj`:

   1. **Output is cards, not rules.** The four whitepaper categories
      are carried via `:card/category`. Tier starts at `:ethic` — the
      default landing tier — and promotion happens through accumulated
      observations, not by the extractor deciding a tier.
   2. **Output is deltas, not files.** The extractor produces
      `:create-card` and `:observe-card` deltas. Writes go through
      `store/staging/append-delta!`. PreCompact (and only PreCompact)
      actually writes cards to disk.

   This namespace is LLM-only. It does not write files. It does not
   read cards. The caller supplies transcript text + existing-card
   ids (for dedup)."
  (:require [clojure.string :as str]
            [succession.identity.llm.claude :as claude]))

;; ------------------------------------------------------------------
;; Prompt construction
;; ------------------------------------------------------------------

(def ^:private extraction-schema
  "{\"cards\": [
     {
       \"id\": \"kebab-case-id\",
       \"category\": \"strategy|failure-inheritance|relational-calibration|meta-cognition\",
       \"text\": \"one or two sentences expressing the claim in first person\",
       \"provenance_context\": \"brief quote or summary of the originating event\",
       \"fingerprint\": \"optional: tool=X,cmd=...\",
       \"tags\": [\"optional\", \"free-form\"]
     }
   ],
   \"observations\": [
     {
       \"card_id\": \"id of an existing or newly-proposed card\",
       \"kind\": \"confirmed|violated|invoked\",
       \"context\": \"brief context from the transcript\"
     }
   ]}")

(defn build-prompt
  "Build the extraction prompt. Caller supplies:
     :transcript-text    - the windowed transcript (already truncated)
     :existing-card-ids  - seq of already-known ids (for dedup hints)"
  [{:keys [transcript-text existing-card-ids]}]
  (str
    "You are reading a Claude Code session transcript to extract IDENTITY "
    "for the agent. Identity is who the agent IS — not what it was told to "
    "do. Look for:\n\n"

    "1. CORRECTIONS — user told the agent to stop or change approach.\n"
    "2. CONFIRMATIONS — user validated a non-obvious agent choice.\n"
    "3. PREFERENCES — how the user wants the agent to work.\n"
    "4. LEARNED FAILURES — moments where the agent failed in a way that\n"
    "   reveals an identity-level lesson.\n\n"

    "Classify each finding into ONE of the four knowledge categories:\n"
    "- strategy: how the agent approaches problems (workflow, methodology)\n"
    "- failure-inheritance: patterns of failure to avoid (anti-patterns)\n"
    "- relational-calibration: communication style (tone, verbosity, depth)\n"
    "- meta-cognition: which heuristics are reliable vs. sound plausible\n\n"

    "EXISTING CARDS (do not duplicate):\n"
    (if (seq existing-card-ids)
      (str/join "\n" (map #(str "- " %) existing-card-ids))
      "(none)")
    "\n\n"

    "TRANSCRIPT:\n"
    transcript-text "\n\n"

    "Output ONLY a JSON object (no markdown fencing). Prefer fewer high-"
    "quality cards over many weak ones. Every card starts at tier :ethic — "
    "do NOT assign a tier. Observation kinds: confirmed (behavior matched), "
    "violated (behavior contradicted), invoked (agent acted on the card "
    "after it was surfaced).\n\n"

    "Schema: " extraction-schema))

;; ------------------------------------------------------------------
;; Response parsing → delta payloads
;; ------------------------------------------------------------------

(def ^:private valid-categories
  #{:strategy :failure-inheritance :relational-calibration :meta-cognition})

(def ^:private valid-kinds
  #{:confirmed :violated :invoked})

(defn- normalize-category [v]
  (let [k (some-> v name str/lower-case keyword)]
    (when (valid-categories k) k)))

(defn- normalize-kind [v]
  (let [k (some-> v name str/lower-case keyword)]
    (when (valid-kinds k) k)))

(defn parse-card
  "Parse one element of the cards array into a create-card payload.
   Returns nil on invalid shape."
  [obj]
  (when (and (map? obj) (:id obj) (:text obj))
    (when-let [cat (normalize-category (:category obj))]
      {:id          (:id obj)
       :category    cat
       :text        (:text obj)
       :fingerprint (:fingerprint obj)
       :tags        (when (seq (:tags obj)) (mapv keyword (:tags obj)))
       :provenance-context (:provenance_context obj)})))

(defn parse-observation
  [obj]
  (when (and (map? obj) (:card_id obj))
    (when-let [kind (normalize-kind (:kind obj))]
      {:card-id (:card_id obj)
       :kind    kind
       :context (or (:context obj) "")})))

(defn parse-response
  "Parse a full extraction response into `{:cards [...] :observations [...]}`.
   Returns nil on parse failure; empty vectors are fine."
  [text]
  (when-let [parsed (claude/parse-json text)]
    (when (map? parsed)
      {:cards        (vec (keep parse-card (:cards parsed)))
       :observations (vec (keep parse-observation (:observations parsed)))})))

;; ------------------------------------------------------------------
;; Synchronous entry
;; ------------------------------------------------------------------

(defn extract
  "Run extraction against a transcript window. Returns a map:

     {:cards ... :observations ... :call-result {:ok? :cost-usd :latency-ms}}

   The caller (hook/stop or cli/replay) converts cards+observations
   into deltas and appends them to the staging log.

   Sonnet only — extraction is a single batch pass, never Haiku,
   never escalated."
  [ctx config]
  (let [prompt (build-prompt ctx)
        cfg    (:reconcile/llm config)
        model  (or (:model cfg) "claude-sonnet-4-6")
        timeout (or (:timeout-seconds cfg) 120)
        result (claude/call prompt {:model-id    model
                                    :timeout-secs timeout
                                    :input-toks  2000
                                    :output-toks 600})
        parsed (when (:ok? result) (parse-response (:text result)))]
    {:cards        (or (:cards parsed) [])
     :observations (or (:observations parsed) [])
     :ok?          (boolean (:ok? result))
     :cost-usd     (or (:cost-usd result) 0.0)
     :latency-ms   (or (:latency-ms result) 0)
     :model        model}))
