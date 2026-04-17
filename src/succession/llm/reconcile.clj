(ns succession.llm.reconcile
  "LLM-assisted contradiction resolution for the categories the pure
   detector cannot fully decide:

     Category 1 - Self-contradictory card. Pure pass detects it;
                  LLM rewrites it to be internally consistent.
     Category 2 - Semantic opposition between two cards. Needs actual
                  text interpretation to find a scope partition.
     Category 3 at :principle tier - a :violated observation against a
                  principle-tier card is a contradiction, not just a
                  weight penalty. The LLM proposes a resolution
                  (rewrite, demote, or escalate).
     Category 6 - Contextual override. Pure pass detects it; LLM either
                  scope-qualifies the rule or marks it intentional.

   Categories 4, 5 are handled entirely by the pure pass and do not
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

(def ^:private category-1-self-contradictory-schema
  "{\"category\": \"self-contradictory\",
    \"kind\": \"rewrite\",
    \"card_id\": \"the card id\",
    \"proposed_text\": \"new internally consistent text\",
    \"rationale\": \"one or two sentences explaining what was contradictory\",
    \"confidence\": 0.0-1.0}")

(def ^:private category-6-contextual-override-schema
  "{\"category\": \"contextual-override\",
    \"kind\": \"scope-qualify|intentional\",
    \"card_id\": \"the card id\",
    \"proposed_text\": \"new text with explicit scope if scope-qualify, or null if intentional\",
    \"rationale\": \"one or two sentences\",
    \"confidence\": 0.0-1.0}")

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

(defn build-category-1-prompt
  "Prompt for resolving a self-contradictory card (Category 1). Caller
   supplies the card whose text contains mutually exclusive directives."
  [{:keys [card]}]
  (str
    "You are the reconcile voice of an agent's identity. This rule card "
    "contains internally contradictory directives — it tells the agent "
    "to do mutually exclusive things.\n\n"
    "CARD:\n" (render-card card) "\n\n"
    "Rewrite the card to be internally self-consistent. Preserve the "
    "core intent. If the contradiction implies two distinct scenarios, "
    "add explicit 'when' scope predicates rather than collapsing them.\n\n"
    "If the card is NOT actually self-contradictory — i.e. its directives "
    "are consistent or merely complementary — return confidence ≤ 0.3 and "
    "explain in rationale why no rewrite is needed. Do not force a rewrite "
    "on a consistent card.\n\n"
    "Return ONLY a JSON object matching this schema:\n"
    category-1-self-contradictory-schema))

(defn build-category-6-prompt
  "Prompt for resolving a contextual-override contradiction (Category 6).
   Caller supplies the card and an optional prior-description from the
   pure detector's analysis."
  [{:keys [card prior-description]}]
  (str
    "You are the reconcile voice of an agent's identity. A rule is "
    "consistently overridden in certain contexts. This suggests either "
    "the rule needs a scope qualifier, or the override is intentional.\n\n"
    "CARD:\n" (render-card card) "\n\n"
    "OVERRIDE PATTERN (from pure analysis):\n  " (or prior-description "") "\n\n"
    "Choose:\n"
    "- scope-qualify (DEFAULT) — the rule is correct but too broad; rewrite\n"
    "  with an explicit scope predicate. You MUST include proposed_text with\n"
    "  the rewritten card text. When in doubt, choose this.\n"
    "- intentional — ONLY choose this when the prior description contains\n"
    "  explicit user intent language such as \"user explicitly said\",\n"
    "  \"intentional policy\", \"by design\", or equivalent. Behavioral\n"
    "  patterns alone (e.g. \"consistently ignored\", \"overridden in\n"
    "  practice\") are NOT sufficient for intentional — those indicate the\n"
    "  rule needs scoping. proposed_text should be null.\n\n"
    "Return ONLY a JSON object matching this schema:\n"
    category-6-contextual-override-schema))

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
    "  reinforcement. The loser is either demoted or rewritten.\n"
    "- If the two cards address clearly unrelated topics with no overlapping\n"
    "  scope, return confidence ≤ 0.3 and explain why no resolution is needed.\n"
    "  Cards that both use universal quantifiers (\"all\", \"always\", \"every\")\n"
    "  on potentially overlapping data domains DO conflict — do not dismiss\n"
    "  those as complementary.\n\n"

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
;; Batch prompt construction
;; ------------------------------------------------------------------

(declare parse-generic)

(def ^:private batch-category-schemas
  "Compact category-specific field reference shown once at the end of
   the batch prompt. Generic illustrative examples — not test fixtures."
  (str
    "Category-specific fields by category value:\n\n"
    "  self-contradictory:   kind=rewrite, proposed_text=string\n"
    "  contextual-override:  kind=scope-qualify|intentional, proposed_text=string-or-null\n"
    "  semantic-opposition:  kind=scope-partition|tier-wins|rewrite-loser,\n"
    "                        scope_a=string-or-null, scope_b=string-or-null,\n"
    "                        winner_card_id=string, loser_card_id=string,\n"
    "                        proposed_text=string-or-null\n"
    "  principle-violated:   kind=rewrite|demote|escalate,\n"
    "                        proposed_text=string-or-null,\n"
    "                        proposed_tier=rule|ethic|null"))

(defn- render-contradiction-block
  "Render one contradiction as a numbered prompt block."
  [idx c cards-by-id]
  (let [cat       (:contradiction/category c)
        cid       (:contradiction/id c)
        between   (:contradiction/between c)
        card-id-a (get-in between [0 :card/id])
        card-id-b (get-in between [1 :card/id])
        card-a    (when card-id-a (get cards-by-id card-id-a))
        card-b    (when card-id-b (get cards-by-id card-id-b))]
    (str
      "--- Contradiction " idx " ---\n"
      "contradiction_id: " cid "\n"
      "category: " (name cat) "\n\n"
      (case cat
        :self-contradictory
        (str "CARD:\n" (if card-a (render-card card-a) "(card not found)") "\n")

        :contextual-override
        (str "CARD:\n" (if card-a (render-card card-a) "(card not found)") "\n"
             "OVERRIDE PATTERN: "
             (or (get-in c [:contradiction/resolution :description]) "") "\n")

        :semantic-opposition
        (str "CARD A:\n" (if card-a (render-card card-a) "(card A not found)") "\n\n"
             "CARD B:\n" (if card-b (render-card card-b) "(card B not found)") "\n")

        :principle-violated
        (str "PRINCIPLE CARD:\n" (if card-a (render-card card-a) "(card not found)") "\n"
             "VIOLATING OBSERVATION context: "
             (or (get-in c [:contradiction/resolution :description]) "") "\n")

        ;; fallback for any unrecognised category
        (str "details: " (pr-str (select-keys c [:contradiction/category :contradiction/between])) "\n")))))

(defn- build-batch-prompt
  "Build one prompt covering all contradictions in `contradictions`.
   The model must return a JSON array — one object per contradiction —
   in any order."
  [contradictions cards-by-id]
  (str
    "You are the reconcile voice of an agent's identity. The following "
    "open contradictions each need a resolution. Analyse each one and "
    "return a JSON array — one object per contradiction — in any order.\n\n"
    "If a contradiction is not genuine (cards are complementary or internally "
    "consistent), return confidence ≤ 0.3 for that entry.\n\n"
    (str/join "\n" (map-indexed
                     (fn [i c] (render-contradiction-block (inc i) c cards-by-id))
                     contradictions))
    "\n" batch-category-schemas "\n\n"
    "Each JSON object in the array must include:\n"
    "  - \"contradiction_id\": exact id from the block header\n"
    "  - \"category\": the category string from the block header\n"
    "  - \"kind\": one of the valid kinds for that category (see above)\n"
    "  - \"rationale\": one or two sentences explaining the resolution\n"
    "  - \"confidence\": 0.0–1.0\n"
    "  - plus any category-specific fields listed above\n\n"
    "Example response shape (generic — illustrative only):\n"
    "[\n"
    "  {\"contradiction_id\": \"c-aaa\", \"category\": \"self-contradictory\",\n"
    "   \"kind\": \"rewrite\", \"rationale\": \"...\", \"confidence\": 0.85,\n"
    "   \"proposed_text\": \"revised card text\"},\n"
    "  {\"contradiction_id\": \"c-bbb\", \"category\": \"semantic-opposition\",\n"
    "   \"kind\": \"scope-partition\", \"rationale\": \"...\", \"confidence\": 0.90,\n"
    "   \"scope_a\": \"when X\", \"scope_b\": \"when Y\",\n"
    "   \"winner_card_id\": \"card-x\", \"loser_card_id\": \"card-y\"}\n"
    "]\n"
    "Return ONLY the JSON array — no prose, no markdown fences."))

(defn- parse-batch-response
  "Parse the LLM batch response `text` into a vec of resolution records,
   one per entry in `contradictions`. Any input contradiction whose
   `contradiction_id` is absent from the LLM response gets
   `{:ok? false :resolution nil}` so callers can skip it cleanly."
  [text contradictions]
  (let [parsed (claude/parse-json text)
        arr    (cond
                 (sequential? parsed) parsed
                 (map? parsed)        [parsed]
                 :else                [])
        by-id  (into {}
                     (keep (fn [obj]
                             (when-let [cid (or (:contradiction_id obj)
                                               (get obj "contradiction_id"))]
                               [cid (parse-generic obj)]))
                           arr))]
    (mapv (fn [c]
            (let [cid        (:contradiction/id c)
                  resolution (get by-id cid)]
              {:contradiction-id cid
               :resolution       resolution
               :ok?              (boolean resolution)}))
          contradictions)))

;; ------------------------------------------------------------------
;; Batch public entry
;; ------------------------------------------------------------------

(defn resolve-open!
  "Call the reconcile LLM once for all `contradictions`. Returns a vec
   of `{:contradiction-id str :resolution map-or-nil :ok? bool}` — one
   entry per input contradiction. Returns `[]` immediately if
   `contradictions` is empty (no LLM call made).

   `max-batch-size` from config caps the slice passed to the prompt to
   keep token count bounded."
  [contradictions cards-by-id config]
  (if (empty? contradictions)
    []
    (let [cfg     (:reconcile/llm config)
          model   (or (:model cfg) "deepseek/deepseek-chat")
          timeout (or (:timeout-seconds cfg) 90)
          prompt  (build-batch-prompt contradictions cards-by-id)
          result  (transport/call prompt {:model-id     model
                                          :timeout-secs  timeout
                                          :output-toks   (* 320 (count contradictions))})]
      (if (:ok? result)
        (parse-batch-response (:text result) contradictions)
        (mapv (fn [c]
                {:contradiction-id (:contradiction/id c)
                 :resolution       nil
                 :ok?              false})
              contradictions)))))

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
        model   (or (:model cfg) "deepseek/deepseek-chat")
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
        model   (or (:model cfg) "deepseek/deepseek-chat")
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

(defn resolve-self-contradictory
  "Call the reconcile LLM for a self-contradictory contradiction (Category 1).
   Returns {:resolution record :ok? :cost-usd :latency-ms :model}."
  [{:keys [contradiction cards-by-id]} config]
  (let [card-id (get-in contradiction [:contradiction/between 0 :card/id])
        card    (get cards-by-id card-id)
        cfg     (:reconcile/llm config)
        model   (or (:model cfg) "deepseek/deepseek-chat")
        timeout (or (:timeout-seconds cfg) 60)
        prompt  (build-category-1-prompt {:card card})
        result  (transport/call prompt {:model-id model :timeout-secs timeout :output-toks 320})]
    {:resolution (when (:ok? result)
                   (when-let [parsed (parse-response (:text result))]
                     (assoc parsed :new-text (get-in parsed [:payload :proposed_text]))))
     :ok?        (boolean (:ok? result))
     :cost-usd   (or (:cost-usd result) 0.0)
     :latency-ms (or (:latency-ms result) 0)
     :model      model}))

(defn resolve-contextual-override
  "Call the reconcile LLM for a contextual-override contradiction (Category 6)."
  [{:keys [contradiction cards-by-id]} config]
  (let [card-id    (get-in contradiction [:contradiction/between 0 :card/id])
        card       (get cards-by-id card-id)
        prior-desc (get-in contradiction [:contradiction/resolution :description])
        cfg        (:reconcile/llm config)
        model      (or (:model cfg) "deepseek/deepseek-chat")
        timeout    (or (:timeout-seconds cfg) 60)
        prompt     (build-category-6-prompt {:card card :prior-description prior-desc})
        result     (transport/call prompt {:model-id model :timeout-secs timeout :output-toks 320})]
    {:resolution (when (:ok? result)
                   (when-let [parsed (parse-response (:text result))]
                     (assoc parsed :new-text (get-in parsed [:payload :proposed_text]))))
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
