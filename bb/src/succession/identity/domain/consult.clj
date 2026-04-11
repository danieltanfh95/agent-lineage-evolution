(ns succession.identity.domain.consult
  "Pure query over cards for a consultation situation.

   `cli/consult` reads promoted.edn + staging, runs this pure function to
   produce a candidate set, then feeds the candidates into a `claude -p`
   call for reflection. This namespace produces the candidate set only —
   no LLM, no I/O, no prompt text. Splitting pure selection from LLM
   reflection keeps the query deterministic and replayable.

   Shape of the returned value:

     {:consult/situation    <the input situation map>
      :consult/candidates   [{:card ... :score ... :category :strategy} ...]
      :consult/by-tier      {:principle [...] :rule [...] :ethic [...]}
      :consult/by-category  {:strategy [...] ...}
      :consult/tensions     [{:tension/kind :principle-forbids :card ...
                              :tension/note \"...\"} ...]
      :consult/top-k        N}

   Tensions are the pure, mechanically-detectable conflicts between
   candidate cards and the situation. They surface a subset of the
   reconcile categories that are relevant in-the-moment:

     :principle-forbids - a principle-tier candidate has a fingerprint
                          match against the situation descriptor. This
                          is the clearest tension: an inviolable card
                          recognised the operation.
     :tier-split        - two candidates at the same category disagree
                          on tier (one :principle, another :ethic). The
                          category is in flux.
     :contradiction-adjacent - a candidate card has an open contradiction
                               record in the situation's `:situation/contradictions`
                               seq.

   Reflective resolution (should the agent do it or not) is for `claude -p`,
   not for this function."
  (:require [succession.identity.domain.salience :as salience]))

(defn- principle-forbids?
  "A principle-tier card that fingerprint-matches the situation is
   a clear, pure-detected tension. Same match logic as salience's
   fingerprint feature."
  [card situation]
  (and (= :principle (:card/tier card))
       (if-let [fp (:card/fingerprint card)]
         (clojure.string/includes?
           (or (:situation/tool-descriptor situation) "") fp)
         false)))

(defn- tier-split
  "Given a seq of scored candidates, return tension records for any
   category that has cards at both :principle and :ethic. A tier split
   means the agent's identity for this category is contested."
  [candidates]
  (let [by-cat (group-by (comp :card/category :card) candidates)]
    (for [[cat cards] by-cat
          :let [tiers (set (map (comp :card/tier :card) cards))]
          :when (and (contains? tiers :principle)
                     (contains? tiers :ethic))]
      {:tension/kind :tier-split
       :tension/category cat
       :tension/cards (mapv :card cards)
       :tension/note (format "category %s has cards at both :principle and :ethic" cat)})))

(defn- contradiction-adjacent
  "Return tensions for candidates whose card-id appears in the
   situation's open contradictions."
  [candidates situation]
  (let [open (or (:situation/contradictions situation) [])
        ids-with-open
        (->> open
             (mapcat :contradiction/between)
             (map :card/id)
             set)]
    (for [c candidates
          :when (contains? ids-with-open (:card/id (:card c)))]
      {:tension/kind :contradiction-adjacent
       :tension/card (:card c)
       :tension/note (format "card %s has an unresolved contradiction"
                             (:card/id (:card c)))})))

(defn- detect-tensions
  [candidates situation]
  (concat
    (for [c candidates
          :when (principle-forbids? (:card c) situation)]
      {:tension/kind :principle-forbids
       :tension/card (:card c)
       :tension/note (format "principle %s matches this operation"
                             (:card/id (:card c)))})
    (tier-split candidates)
    (contradiction-adjacent candidates situation)))

(defn query
  "Pure consultation query.

   Inputs:
     scored-cards - seq of `{:card <card> :weight <num> :recency-fraction <num>}`
                    as consumed by salience. Caller (cli/consult) builds
                    this from promoted.edn + staging snapshot + observation
                    rollups.
     situation    - map with:
                      :situation/text               - free-form consult query
                      :situation/tags               - optional, for salience
                      :situation/tool-descriptor    - optional, for fingerprint match
                      :situation/contradictions     - optional seq of open
                                                      contradiction records
     config       - effective config

   Returns a candidate-set map. Deterministic, pure."
  [scored-cards situation config]
  (let [ranked      (salience/rank scored-cards situation config)
        candidates  ranked
        by-tier     (group-by (comp :card/tier :card) candidates)
        by-category (group-by (comp :card/category :card) candidates)
        tensions    (vec (detect-tensions candidates situation))
        top-k       (get-in config [:salience/profile :top-k])]
    {:consult/situation   situation
     :consult/candidates  candidates
     :consult/by-tier     by-tier
     :consult/by-category by-category
     :consult/tensions    tensions
     :consult/top-k       top-k}))
