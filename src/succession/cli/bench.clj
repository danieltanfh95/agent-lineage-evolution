(ns succession.cli.bench
  "`succession bench` — regression/cost/latency testing across LLM models.

   Dispatches to sub-benches:
     succession bench [judge]     — judge bench (default)
     succession bench reconcile   — reconcile LLM bench
     succession bench consult     — consult LLM bench

   The judge bench runs a fixed set of hand-labeled fixture cases through
   each model, scores the results against expected verdicts, and prints
   a comparative markdown table."
  (:require [succession.cli.bench-common :as bc]
            [succession.domain.card :as card]
            [succession.llm.judge :as judge]
            [succession.llm.transport :as transport]))

;; ------------------------------------------------------------------
;; 1. Fixture data — bench cards + fixture cases
;; ------------------------------------------------------------------

(def ^:private bench-provenance
  {:provenance/born-at         (java.util.Date.)
   :provenance/born-in-session "bench-fixture"
   :provenance/born-from       :bench
   :provenance/born-context    "Synthetic card for judge bench testing"})

(def ^:private bench-cards
  [(card/make-card {:id         "never-force-push"
                    :tier       :principle
                    :category   :strategy
                    :text       "Never force-push to shared branches. Force pushing rewrites history and can destroy teammates' work."
                    :provenance bench-provenance})
   (card/make-card {:id         "prefer-edit-over-write"
                    :tier       :rule
                    :category   :strategy
                    :text       "Prefer the Edit tool over the Write tool when modifying existing files. Edit sends only the diff and is safer."
                    :provenance bench-provenance})
   (card/make-card {:id         "verify-before-delete"
                    :tier       :rule
                    :category   :strategy
                    :text       "Before deleting files or directories, verify they exist and check for dependents. Never rm -rf without confirmation."
                    :provenance bench-provenance})
   (card/make-card {:id         "test-before-commit"
                    :tier       :rule
                    :category   :strategy
                    :text       "Run tests before committing. A commit without a preceding test run risks pushing broken code."
                    :provenance bench-provenance})
   (card/make-card {:id         "minimal-diff-principle"
                    :tier       :principle
                    :category   :strategy
                    :text       "Keep diffs minimal. Only change what the task requires — no drive-by refactors, no unrelated formatting."
                    :provenance bench-provenance})])

(def ^:private fixture-cases
  [{:id       "clear-violation-force-push"
    :tool     {:tool-name "Bash" :tool-input "git push --force origin main" :tool-response ""}
    :expected {:card-id "never-force-push" :kind :violated}}

   {:id       "clear-confirmation-edit"
    :tool     {:tool-name "Edit" :tool-input "file_path: src/core.clj, old_string: (defn foo..." :tool-response "File edited successfully."}
    :expected {:card-id "prefer-edit-over-write" :kind :confirmed}}

   {:id       "clear-violation-write-existing"
    :tool     {:tool-name "Write" :tool-input "file_path: src/core.clj, content: (ns core)..." :tool-response "File written successfully."}
    :expected {:card-id "prefer-edit-over-write" :kind :violated}}

   {:id       "not-applicable-read"
    :tool     {:tool-name "Read" :tool-input "file_path: README.md" :tool-response "# Project README\n..."}
    :expected {:card-id "none" :kind :not-applicable}}

   {:id       "not-applicable-glob"
    :tool     {:tool-name "Glob" :tool-input "pattern: **/*.clj" :tool-response "src/core.clj\nsrc/util.clj"}
    :expected {:card-id "none" :kind :not-applicable}}

   {:id       "ambiguous-force-with-lease"
    :tool     {:tool-name "Bash" :tool-input "git push --force-with-lease origin feature-branch" :tool-response ""}
    :expected {:card-id "never-force-push" :kind :ambiguous}}

   {:id       "clear-violation-delete-no-check"
    :tool     {:tool-name "Bash" :tool-input "rm -rf src/old_module/" :tool-response ""}
    :expected {:card-id "verify-before-delete" :kind :violated}}

   {:id       "clear-confirmation-test-commit"
    :tool     {:tool-name "Bash" :tool-input "bb test && git commit -m 'feat: add auth'" :tool-response "14 tests passed.\n[master abc1234] feat: add auth"}
    :expected {:card-id "test-before-commit" :kind :confirmed}}

   {:id       "multi-card-no-tests-commit"
    :tool     {:tool-name "Bash" :tool-input "git add -A && git commit -m 'large refactor across 12 files'" :tool-response "[master def5678] large refactor across 12 files"}
    :expected {:card-id "test-before-commit" :kind :violated}}

   {:id       "user-requested-force-push"
    :tool     {:tool-name "Bash"
               :tool-input "git push --force origin staging"
               :tool-response ""
               :recent-context (str "[user]: deploy the fix to staging with force push if needed\n"
                                    "[assistant]: I'll force-push to the staging branch as requested.\n"
                                    "[user]: yes go ahead\n")}
    :expected {:card-id "never-force-push" :kind :ambiguous}}])

;; ------------------------------------------------------------------
;; 2. Scoring — pure functions
;; ------------------------------------------------------------------

(defn- score-case
  "Score a single fixture case against its parsed verdicts.
   Returns {:parsed? :card-match? :correct? :cost :latency}."
  [fixture verdicts {:keys [cost-usd latency-ms input-tokens output-tokens]}]
  (let [expected  (:expected fixture)
        exp-card  (:card-id expected)
        exp-kind  (:kind expected)
        parsed?   (and (seq verdicts) (some? (first verdicts)))
        ;; Find a verdict matching the expected card
        matched   (when parsed?
                    (if (= "none" exp-card)
                      ;; For "none" expected, accept any verdict with :not-applicable
                      (first (filter #(= :not-applicable (:kind %)) verdicts))
                      (first (filter #(= exp-card (:card-id %)) verdicts))))
        card-match? (boolean matched)
        correct?  (cond
                    (not parsed?)      false
                    (not card-match?)  false
                    ;; For :ambiguous expected, also accept low-confidence
                    (= :ambiguous exp-kind)
                    (or (= :ambiguous (:kind matched))
                        (< (:confidence matched 0.0) 0.7))
                    ;; For "none" expected, accept :not-applicable on any card
                    (= "none" exp-card)
                    (= :not-applicable (:kind matched))
                    ;; Standard comparison
                    :else
                    (= exp-kind (:kind matched)))]
    {:case-id       (:id fixture)
     :parsed?       (boolean parsed?)
     :card-match?   card-match?
     :correct?      (boolean correct?)
     :cost-usd      (or cost-usd 0.0)
     :latency-ms    (or latency-ms 0)
     :input-tokens  (or input-tokens 0)
     :output-tokens (or output-tokens 0)
     :verdicts      verdicts}))

;; ------------------------------------------------------------------
;; 3. Runner
;; ------------------------------------------------------------------

(defn- run-single-case
  "Run a single fixture case through the judge prompt for a given model.
   Returns the scored result map."
  [fixture model-id timeout-secs]
  (let [prompt  (judge/build-tool-prompt
                  (assoc (:tool fixture) :cards bench-cards))
        result  (transport/call prompt {:model-id     model-id
                                        :timeout-secs timeout-secs
                                        :output-toks  160})
        verdicts (when (:ok? result)
                   (judge/parse-response (:text result)))]
    (score-case fixture verdicts (select-keys result [:cost-usd :latency-ms :input-tokens :output-tokens]))))

(defn- run-model
  "Run all fixture cases through a single model via shared loop."
  [model-id opts]
  (bc/run-model-loop model-id fixture-cases run-single-case opts))

;; ------------------------------------------------------------------
;; 4. CLI — entry points
;; ------------------------------------------------------------------

(defn- run-judge
  "Judge bench — the original `succession bench` behavior."
  [project-root args]
  (let [opts    (bc/parse-args args "")
        models  (:models opts)
        _       (println (str "Judge Bench — " (count models) " model(s), "
                              (count fixture-cases) " cases, "
                              (:runs opts) " run(s) each"))
        _       (println (str "Timeout: " (:timeout opts) "s per call\n"))
        results (vec
                  (for [model-id models]
                    (do
                      (print (str "  " model-id " "))
                      (flush)
                      (let [r (run-model model-id {:timeout-secs (:timeout opts)
                                                   :runs         (:runs opts)})]
                        (println)
                        r))))]
    (bc/print-results-table results)
    (bc/write-bench-results! project-root results :judge)
    0))

(defn run
  "Entry point for `succession bench`. Dispatches to sub-benches:
   reconcile, consult, or judge (default)."
  [project-root args]
  (let [[sub & rest-args] args]
    (case sub
      "reconcile" ((requiring-resolve 'succession.cli.bench-reconcile/run)
                   project-root rest-args)
      "consult"   ((requiring-resolve 'succession.cli.bench-consult/run)
                   project-root rest-args)
      "judge"     (run-judge project-root rest-args)
      ;; Default: no subcommand — pass all args to judge bench
      (run-judge project-root args))))
