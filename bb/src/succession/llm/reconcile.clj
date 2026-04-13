(ns succession.llm.reconcile
  "LLM-assisted contradiction resolution for the categories the pure
   detector cannot decide:

     Category 2 - Semantic opposition between two cards. Needs actual
                  text interpretation to find a scope partition.
     Category 3 at :principle tier - a :violated observation against a
                  principle-tier card is a contradiction, not just a
                  weight penalty. The LLM proposes a resolution
                  (rewrite, demote, or escalate).

   Categories 1, 4, 5, 6 are detected by `domain/reconcile` and do not
   need the LLM.

   Every response is normalised into a resolution record that the
   caller writes back to the canonical contradiction file via
   `store/contradictions/mark-resolved!` (when confidence ≥
   auto-apply-confidence) or flags for user escalation.

   Reference: `.plans/succession-identity-cycle.md` §Reconcile categories,
   §Reconcile pipeline."
  (:require [clojure.string :as str]
            [succession.llm.claude :as claude]
            [succession.llm.transport :as transport]))

;; ------------------------------------------------------------------
;; Prompt construction
;; ------------------------------------------------------------------

(def ^:private category-2-schema
  "{\"category\": \"semantic-opposition\",
    \"kind\": \"scope-partition|tier-wins|rewrite-loser\",
    \"scope_a\": \"predicate that applies to card A, or null\",
    \"scope_b\": \"predicate that applies to card B, or null\",
    \"winner_card_id\": \"id of the surviving card\",
    \"loser_card_id\": \"id of the losing card\",
    \"proposed_text\": \"new text if the loser is rewritten, or null\",
    \"rationale\": \"one or two sentences\",
    \"confidence\": 0.0-1.0}")

(def ^:private category-3-principle-schema
  "{\"category\": \"principle-violated\",
    \"kind\": \"rewrite|demote|escalate\",
    \"card_id\": \"the principle-tier card in question\",
    \"proposed_text\": \"new card text if rewrite, or null\",
    \"proposed_tier\": \"if demote, one of :rule or :ethic; else null\",
    \"rationale\": \"one or two sentences\",
    \"confidence\": 0.0-1.0}")

(defn- render-card [c]
  (format "id=%s tier=%s category=%s\n  text: %s\n  tags: %s"
          (:card/id c)
          (name (:card/tier c))
          (name (:card/category c))
          (first (str/split-lines (or (:card/text c) "")))
          (pr-str (or (:card/tags c) []))))

(defn- render-observation-window [obs-seq]
  (->> obs-seq
       (take-last 10)
       (map (fn [o]
              (format "  %s %s: %s"
                      (str (:observation/at o))
                      (name (:observation/kind o))
                      (or (:observation/context o) ""))))
       (str/join "\n")))

(defn build-category-2-prompt
  "Prompt for resolving a semantic-opposition contradiction between
   two cards (Category 2). Caller supplies both cards and their recent
   observation windows."
  [{:keys [card-a card-b obs-a obs-b]}]
  (str
    "You are the reconcile voice of an agent's identity. Two cards "
    "appear semantically opposed. Decide whether they can coexist via "
    "a scope partition, or whether one must lose.\n\n"

    "CARD A:\n" (render-card card-a) "\n"
    "RECENT OBSERVATIONS ON A:\n" (render-observation-window obs-a) "\n\n"

    "CARD B:\n" (render-card card-b) "\n"
    "RECENT OBSERVATIONS ON B:\n" (render-observation-window obs-b) "\n\n"

    "Rules:\n"
    "- If a distinguishing scope predicate exists, propose :scope-partition\n"
    "  with concrete `scope_a` and `scope_b` strings.\n"
    "- Else higher tier wins; ties broken by the card with more recent\n"
    "  reinforcement. The loser is either demoted or rewritten.\n\n"

    "Return ONLY a JSON object matching this schema:\n"
    category-2-schema))

(defn build-category-3-principle-prompt
  "Prompt for resolving a principle-tier violation (Category 3 at
   :principle tier)."
  [{:keys [card violating-observation obs-history]}]
  (str
    "You are the reconcile voice of an agent's identity. A principle-tier "
    "card just got a :violated observation. At this tier, that's a "
    "contradiction, not merely a weight penalty. Decide what to do.\n\n"

    "PRINCIPLE CARD:\n" (render-card card) "\n\n"
    "VIOLATING OBSERVATION:\n"
    "  at:     " (str (:observation/at violating-observation)) "\n"
    "  kind:   " (name (:observation/kind violating-observation)) "\n"
    "  context: " (or (:observation/context violating-observation) "") "\n\n"

    "RECENT OBSERVATION HISTORY:\n" (render-observation-window obs-history) "\n\n"

    "Possible kinds:\n"
    "- :rewrite — the principle is still correct but the text needs\n"
    "  refinement to capture the edge case.\n"
    "- :demote — the principle is no longer load-bearing at principle\n"
    "  tier; demote to :rule or :ethic.\n"
    "- :escalate — the conflict cannot be resolved without the user.\n\n"

    "Return ONLY a JSON object matching this schema:\n"
    category-3-principle-schema))

;; ------------------------------------------------------------------
;; Response parsing
;; ------------------------------------------------------------------

(defn- parse-generic [obj]
  (when (and (map? obj) (:category obj) (:kind obj))
    (let [kw (fn [k] (some-> (get obj k) name keyword))]
      {:category   (kw :category)
       :kind       (kw :kind)
       :rationale  (or (:rationale obj) "")
       :confidence (double (or (:confidence obj) 0.0))
       :payload    (dissoc obj :category :kind :rationale :confidence)})))

(defn parse-response
  "Parse a JSON reconcile response into a resolution record. Returns
   nil on invalid shape."
  [text]
  (when-let [parsed (claude/parse-json text)]
    (cond
      (map? parsed)                          (parse-generic parsed)
      (and (sequential? parsed) (seq parsed)) (parse-generic (first parsed))
      :else                                   nil)))

;; ------------------------------------------------------------------
;; Synchronous entries
;; ------------------------------------------------------------------

(defn resolve-category-2
  "Call the reconcile LLM for a category-2 contradiction. Returns
   `{:resolution <record> :ok? :cost-usd :latency-ms}`."
  [ctx config]
  (let [cfg     (:reconcile/llm config)
        model   (or (:model cfg) "claude-sonnet-4-6")
        timeout (or (:timeout-seconds cfg) 60)
        prompt  (build-category-2-prompt ctx)
        result  (transport/call prompt {:model-id    model
                                          :timeout-secs timeout
                                          :output-toks 320})]
    {:resolution (when (:ok? result) (parse-response (:text result)))
     :ok?        (boolean (:ok? result))
     :cost-usd   (or (:cost-usd result) 0.0)
     :latency-ms (or (:latency-ms result) 0)
     :model      model}))

(defn resolve-category-3-principle
  "Call the reconcile LLM for a principle-tier violation contradiction."
  [ctx config]
  (let [cfg     (:reconcile/llm config)
        model   (or (:model cfg) "claude-sonnet-4-6")
        timeout (or (:timeout-seconds cfg) 60)
        prompt  (build-category-3-principle-prompt ctx)
        result  (transport/call prompt {:model-id    model
                                          :timeout-secs timeout
                                          :output-toks 320})]
    {:resolution (when (:ok? result) (parse-response (:text result)))
     :ok?        (boolean (:ok? result))
     :cost-usd   (or (:cost-usd result) 0.0)
     :latency-ms (or (:latency-ms result) 0)
     :model      model}))

(defn auto-applicable?
  "Is a resolution confident enough to auto-apply without user review?
   Threshold from config (:reconcile/llm :auto-apply-confidence).
   Default 0.8."
  [resolution config]
  (let [threshold (or (get-in config [:reconcile/llm :auto-apply-confidence]) 0.8)]
    (and resolution
         (number? (:confidence resolution))
         (>= (:confidence resolution) threshold))))
