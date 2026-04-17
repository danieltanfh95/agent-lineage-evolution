(ns succession.cli.bench-reconcile
  "`succession bench reconcile` — measures model quality on the reconcile task.

   Runs a fixed set of hand-labeled fixture cases through each model's
   reconcile prompt pipeline (categories 1, 2, 3, 6), scores results
   against expected resolutions, and prints a comparative table."
  (:require [succession.cli.bench-common :as bc]
            [succession.domain.card :as card]
            [succession.llm.reconcile :as reconcile]
            [succession.llm.transport :as transport]))

;; ------------------------------------------------------------------
;; 1. Fixture data
;; ------------------------------------------------------------------

(def ^:private bench-provenance
  {:provenance/born-at         (java.util.Date.)
   :provenance/born-in-session "bench-fixture"
   :provenance/born-from       :bench
   :provenance/born-context    "Synthetic card for reconcile bench testing"})

(def ^:private card-self-contra
  (card/make-card {:id         "card-self-contra"
                   :tier       :rule
                   :category   :strategy
                   :text       "Always use mocks in tests. Never use mocks — they hide real bugs."
                   :provenance bench-provenance}))

(def ^:private card-scope-a
  (card/make-card {:id         "card-scope-a"
                   :tier       :rule
                   :category   :strategy
                   :text       "Use PostgreSQL for all persistent storage."
                   :provenance bench-provenance}))

(def ^:private card-scope-b
  (card/make-card {:id         "card-scope-b"
                   :tier       :rule
                   :category   :strategy
                   :text       "Use Redis for all data that needs fast access."
                   :provenance bench-provenance}))

(def ^:private card-principle-strict
  (card/make-card {:id         "card-principle-strict"
                   :tier       :principle
                   :category   :strategy
                   :text       "Never bypass CI checks. All code must pass the full test suite before merging."
                   :provenance bench-provenance}))

(def ^:private card-override-pattern
  (card/make-card {:id         "card-override-pattern"
                   :tier       :rule
                   :category   :strategy
                   :text       "Always request review before deploying. Deployments without review are violations."
                   :provenance bench-provenance}))

(def ^:private card-minimal
  (card/make-card {:id         "card-minimal"
                   :tier       :rule
                   :category   :strategy
                   :text       "Always."
                   :provenance bench-provenance}))

(def ^:private card-verbose
  (card/make-card {:id         "card-verbose"
                   :tier       :rule
                   :category   :strategy
                   :text       (str "When modifying any configuration file in the project, "
                                    "always create a backup copy of the original file before "
                                    "making changes. The backup should be stored in the same "
                                    "directory with a .bak extension. If the modification fails "
                                    "or introduces errors detected by the test suite, restore "
                                    "the backup automatically. Never modify configuration files "
                                    "that are currently being read by a running process. Check "
                                    "for file locks before proceeding. If a lock exists, wait "
                                    "up to 30 seconds for it to clear before aborting the "
                                    "modification and reporting the lock conflict to the user.")
                   :provenance bench-provenance}))

;; Synthetic observations for category 2 and 3 prompts

(def ^:private obs-time (java.util.Date.))

(defn- make-obs [kind context]
  {:observation/at      obs-time
   :observation/kind    kind
   :observation/context context})

(def ^:private fixture-cases
  [;; Category 1 — self-contradictory card
   {:id       "cat1-clear-contradiction"
    :category 1
    :input    {:card card-self-contra}
    :expected {:category             :self-contradictory
               :kind                 :rewrite
               :needs-proposed-text? true
               :confidence-floor     0.7}}

   {:id       "cat1-nuanced-not-contradictory"
    :category 1
    :input    {:card card-principle-strict}
    :expected {:negative-case? true
               :max-confidence 0.5}}

   ;; Category 2 — semantic opposition
   {:id       "cat2-clear-opposition"
    :category 2
    :input    {:card-a card-scope-a
               :card-b card-scope-b
               :obs-a  [(make-obs :confirmed "PostgreSQL used for user data storage")
                        (make-obs :confirmed "PostgreSQL used for order records")]
               :obs-b  [(make-obs :confirmed "Redis used for session cache")
                        (make-obs :confirmed "Redis used for rate limiting data")]}
    :expected {:category         :semantic-opposition
               :accept-kinds     #{:scope-partition :tier-wins :rewrite-loser}
               :confidence-floor 0.6}}

   {:id       "cat2-complementary-not-opposing"
    :category 2
    :input    {:card-a card-principle-strict
               :card-b card-override-pattern
               :obs-a  [(make-obs :confirmed "CI pipeline ran for all PRs this sprint")]
               :obs-b  [(make-obs :confirmed "Code review completed before deployment")]}
    :expected {:negative-case? true
               :max-confidence 0.5}}

   ;; Category 3 — principle violated
   {:id       "cat3-clear-violation"
    :category 3
    :input    {:card                  card-principle-strict
               :violating-observation (make-obs :violated
                                       "merged PR #847 without CI — pipeline was broken, PR merged manually")
               :obs-history           [(make-obs :confirmed "CI passed for PR #820")
                                       (make-obs :confirmed "CI passed for PR #835")
                                       (make-obs :violated "merged PR #847 without CI")]}
    :expected {:category         :principle-violated
               :accept-kinds     #{:rewrite :demote :escalate}
               :confidence-floor 0.6}}

   {:id       "cat3-edge-case-violation"
    :category 3
    :input    {:card                  card-principle-strict
               :violating-observation (make-obs :violated
                                       "CI skipped per team lead request for emergency hotfix")
               :obs-history           [(make-obs :confirmed "CI passed for all PRs this week")
                                       (make-obs :violated "CI skipped per team lead request")]}
    :expected {:category         :principle-violated
               :accept-kinds     #{:rewrite :escalate}
               :confidence-floor 0.4}}

   ;; Category 6 — contextual override
   {:id       "cat6-clear-scope-qualify"
    :category 6
    :input    {:card              card-override-pattern
               :prior-description (str "3 consecutive confirmed observations after initial "
                                       "violation — rule consistently ignored for hotfixes "
                                       "to production")}
    :expected {:category             :contextual-override
               :kind                 :scope-qualify
               :needs-proposed-text? true
               :confidence-floor     0.6}}

   {:id       "cat6-intentional-override"
    :category 6
    :input    {:card              card-override-pattern
               :prior-description "user explicitly said: skip review for hotfixes, this is intentional policy"}
    :expected {:category         :contextual-override
               :accept-kinds     #{:scope-qualify :intentional}
               :confidence-floor 0.6}}

   ;; Boundary cases
   {:id       "boundary-empty-card-text"
    :category 1
    :input    {:card card-minimal}
    :expected {:parse-only? true}}

   {:id       "boundary-long-card-text"
    :category 1
    :input    {:card card-verbose}
    :expected {:parse-only? true}}])

;; ------------------------------------------------------------------
;; 2. Scoring
;; ------------------------------------------------------------------

(defn- score-reconcile-case
  "Score a single reconcile fixture case against its parsed resolution."
  [fixture resolution metrics]
  (let [expected (:expected fixture)
        parsed?  (some? resolution)
        base     {:case-id       (:id fixture)
                  :parsed?       (boolean parsed?)
                  :cost-usd      (or (:cost-usd metrics) 0.0)
                  :latency-ms    (or (:latency-ms metrics) 0)
                  :input-tokens  (or (:input-tokens metrics) 0)
                  :output-tokens (or (:output-tokens metrics) 0)}]
    (cond
      (:parse-only? expected)
      (assoc base :correct? (boolean parsed?))

      (:negative-case? expected)
      (let [conf     (:confidence resolution 1.0)
            max-conf (:max-confidence expected 0.5)
            correct? (and parsed? (<= conf max-conf))]
        (assoc base
               :correct?       (boolean correct?)
               :confidence-ok? (boolean (and parsed? (<= conf max-conf)))))

      :else
      (let [cat-match?  (and parsed? (= (:category expected) (:category resolution)))
            kind-match? (and parsed?
                             (if (:accept-kinds expected)
                               (contains? (:accept-kinds expected) (:kind resolution))
                               (= (:kind expected) (:kind resolution))))
            conf-ok?    (and parsed?
                             (>= (:confidence resolution 0.0)
                                 (:confidence-floor expected 0.0)))
            text-ok?    (if (:needs-proposed-text? expected)
                          (and parsed? (seq (get-in resolution [:payload :proposed_text])))
                          true)
            correct?    (and parsed? cat-match? kind-match? conf-ok? text-ok?)]
        (assoc base
               :category-match? (boolean cat-match?)
               :kind-match?     (boolean kind-match?)
               :confidence-ok?  (boolean conf-ok?)
               :text-present?   (boolean text-ok?)
               :correct?        (boolean correct?))))))

;; ------------------------------------------------------------------
;; 3. Runner
;; ------------------------------------------------------------------

(defn- run-single-case
  "Run a single fixture case through the reconcile prompt for a model."
  [fixture model-id timeout-secs]
  (let [cat    (:category fixture)
        input  (:input fixture)
        prompt (case cat
                 1 (reconcile/build-category-1-prompt input)
                 2 (reconcile/build-category-2-prompt input)
                 3 (reconcile/build-category-3-principle-prompt input)
                 6 (reconcile/build-category-6-prompt input))
        result (transport/call prompt {:model-id     model-id
                                        :timeout-secs timeout-secs
                                        :output-toks  320})
        resolution (when (:ok? result)
                     (reconcile/parse-response (:text result)))]
    (score-reconcile-case fixture resolution
                          (select-keys result [:cost-usd :latency-ms
                                               :input-tokens :output-tokens]))))

(defn run
  "Entry point for `succession bench reconcile`."
  [project-root args]
  (let [opts    (bc/parse-args args "reconcile")
        models  (:models opts)
        _       (println (str "Reconcile Bench — " (count models) " model(s), "
                              (count fixture-cases) " cases, "
                              (:runs opts) " run(s) each"))
        _       (println (str "Timeout: " (:timeout opts) "s per call\n"))
        results (vec
                  (for [model-id models]
                    (do
                      (print (str "  " model-id " "))
                      (flush)
                      (let [r (bc/run-model-loop model-id fixture-cases run-single-case
                                                  {:timeout-secs (:timeout opts)
                                                   :runs         (:runs opts)})]
                        (println)
                        r))))]
    (bc/print-results-table results)
    (bc/write-bench-results! project-root results :reconcile)
    0))
