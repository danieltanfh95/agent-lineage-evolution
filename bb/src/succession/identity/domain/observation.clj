(ns succession.identity.domain.observation
  "Pure data shape + predicates for observations.

   An observation is the atom of evidence: one thing the judge, the user,
   or a deterministic detector noticed that is relevant to a card. Many
   observations per card, per session. The observation log is the source
   of truth — cards derive weight/tier from it, not the reverse.

   Shape:

     {:succession/entity-type   :observation
      :observation/id           \"obs-abc123\"             ; uuid string
      :observation/at           #inst \"...\"              ; java.time.Instant in bb
      :observation/session      \"session-xyz\"
      :observation/hook         :post-tool-use
      :observation/source       :judge-verdict             ; see valid-sources
      :observation/card-id      \"prefer-edit-over-write\"
      :observation/kind         :confirmed                 ; config/valid-observation-kinds
      :observation/context      \"brief explanatory string\"
      :observation/judge-verdict-id \"v-123\"              ; optional, backref
      :observation/judge-model  \"claude-sonnet-4-6\"}     ; optional, for drift tracking

   All observations are append-only. Never edit, never delete. If wrong,
   add a contradiction record instead."
  (:require [succession.identity.config :as config]))

(def valid-sources
  "Where observations come from."
  #{:judge-verdict        ; LLM judge async lane
    :user-correction      ; UserPromptSubmit detected a correction
    :self-detect          ; PostToolUse fingerprint match
    :consult              ; cli/consult logged a consulted card
    :reconcile            ; pure or LLM reconcile wrote a contradiction-adjacent obs
    :extract})            ; LLM extract at Stop

(def valid-hooks
  "Which hooks can write observations."
  #{:session-start :user-prompt-submit :pre-tool-use :post-tool-use :stop :pre-compact})

(defn observation?
  "Predicate: `x` is a well-formed observation."
  [x]
  (and (map? x)
       (= :observation (:succession/entity-type x))
       (string? (:observation/id x))
       (inst?   (:observation/at x))
       (string? (:observation/session x))
       (contains? valid-hooks   (:observation/hook x))
       (contains? valid-sources (:observation/source x))
       (string? (:observation/card-id x))
       (contains? config/valid-observation-kinds (:observation/kind x))))

(defn make-observation
  "Constructor. Caller must provide `:at`, `:id`, and `:session` — this
   keeps the function pure and replayable. `context` is optional."
  [{:keys [id at session hook source card-id kind context judge-verdict-id judge-model]}]
  {:pre [(string? id)
         (inst? at)
         (string? session)
         (contains? valid-hooks hook)
         (contains? valid-sources source)
         (string? card-id)
         (contains? config/valid-observation-kinds kind)]}
  (cond-> {:succession/entity-type :observation
           :observation/id         id
           :observation/at         at
           :observation/session    session
           :observation/hook       hook
           :observation/source     source
           :observation/card-id    card-id
           :observation/kind       kind}
    context           (assoc :observation/context context)
    judge-verdict-id  (assoc :observation/judge-verdict-id judge-verdict-id)
    judge-model       (assoc :observation/judge-model judge-model)))

(defn weight-contributing?
  "Does this observation affect weight computation?

   Per plan §Consult: `:consulted` observations are weight-neutral. They
   are logged for audit so the agent's consult history is inspectable,
   but they don't inflate freq or reinforce tier promotion — otherwise
   the agent could game reinforcement by consulting more."
  [observation]
  (not= :consulted (:observation/kind observation)))
