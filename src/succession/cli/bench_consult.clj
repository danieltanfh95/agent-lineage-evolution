(ns succession.cli.bench-consult
  "`succession bench consult` — measures model quality on the consult/reflection task.

   Runs fixture situations through the consult pipeline (pure query ->
   render -> framed prompt -> LLM), scores responses via heuristics and
   optionally an LLM judge (Sonnet), and prints a comparative table."
  (:require [clojure.string :as str]
            [succession.cli.bench-common :as bc]
            [succession.cli.consult :as cli-consult]
            [succession.config :as config]
            [succession.domain.card :as card]
            [succession.domain.consult :as consult]
            [succession.domain.render :as render]
            [succession.llm.claude :as claude]
            [succession.llm.transport :as transport]))

;; ------------------------------------------------------------------
;; 1. Fixture data
;; ------------------------------------------------------------------

(def ^:private bench-provenance
  {:provenance/born-at         (java.util.Date.)
   :provenance/born-in-session "bench-fixture"
   :provenance/born-from       :bench
   :provenance/born-context    "Synthetic card for consult bench testing"})

(def ^:private bench-cards
  [(card/make-card {:id          "never-force-push"
                    :tier        :principle
                    :category    :strategy
                    :text        "Never force-push to shared branches. Force pushing rewrites history and can destroy teammates' work."
                    :fingerprint "git push --force"
                    :provenance  bench-provenance})
   (card/make-card {:id          "prefer-edit-over-write"
                    :tier        :rule
                    :category    :strategy
                    :text        "Prefer the Edit tool over the Write tool when modifying existing files. Edit sends only the diff and is safer."
                    :fingerprint "tool=Edit"
                    :provenance  bench-provenance})
   (card/make-card {:id          "verify-before-delete"
                    :tier        :rule
                    :category    :strategy
                    :text        "Before deleting files or directories, verify they exist and check for dependents. Never rm -rf without confirmation."
                    :provenance  bench-provenance})
   (card/make-card {:id          "test-before-commit"
                    :tier        :rule
                    :category    :strategy
                    :text        "Run tests before committing. A commit without a preceding test run risks pushing broken code."
                    :provenance  bench-provenance})
   (card/make-card {:id          "minimal-diff-principle"
                    :tier        :principle
                    :category    :strategy
                    :text        "Keep diffs minimal. Only change what the task requires — no drive-by refactors, no unrelated formatting."
                    :provenance  bench-provenance})
   (card/make-card {:id          "log-before-debug"
                    :tier        :ethic
                    :category    :strategy
                    :text        "Check logs before starting a debug session. Logs often contain the answer without requiring invasive debugging."
                    :provenance  bench-provenance})])

(def ^:private scored-bench-cards
  "Bench cards wrapped as scored-cards for domain/consult/query."
  (mapv (fn [c] {:card c :weight 10.0 :recency-fraction 0.8})
        bench-cards))

(def ^:private fixture-cases
  [{:id        "principle-forbids-force-push"
    :situation {:situation/text            "I need to force push to main"
                :situation/tool-descriptor "tool=Bash,input=git push --force origin main"
                :situation/contradictions  []}
    :expected  {:must-mention-cards  #{"never-force-push"}
                :must-flag-tensions  #{:principle-forbids}
                :required-sections   #{"Principle" "tensions" "reflection"}
                :no-apology?        true}}

   {:id        "clear-edit-confirmation"
    :situation {:situation/text            "editing existing config file"
                :situation/tool-descriptor "tool=Edit,input=file_path: config.edn"
                :situation/contradictions  []}
    :expected  {:must-mention-cards  #{"prefer-edit-over-write"}
                :must-flag-tensions  #{}
                :required-sections   #{"Rule" "reflection"}
                :no-apology?        true}}

   {:id        "tier-split-tension"
    :situation {:situation/text            "deploying without running the full test suite first"
                :situation/tool-descriptor "tool=Bash,input=git push origin main"
                :situation/contradictions  []}
    :expected  {:must-mention-cards  #{}
                :must-flag-tensions  #{:tier-split}
                :required-sections   #{"tensions" "reflection"}
                :no-apology?        true}}

   {:id        "contradiction-adjacent"
    :situation {:situation/text            "checking git log for recent changes"
                :situation/tool-descriptor "tool=Bash,input=git log --oneline -5"
                :situation/contradictions  [{:contradiction/id       "c-bench-test"
                                             :contradiction/category :self-contradictory
                                             :contradiction/between  [{:card/id "never-force-push"}]}]}
    :expected  {:must-mention-cards  #{"never-force-push"}
                :must-flag-tensions  #{:contradiction-adjacent}
                :required-sections   #{"tensions" "reflection"}
                :no-apology?        true}}

   {:id        "no-tensions-benign"
    :situation {:situation/text            "reading a log file to check for errors"
                :situation/tool-descriptor "tool=Read,input=app.log"
                :situation/contradictions  []}
    :expected  {:must-mention-cards  #{}
                :must-flag-tensions  #{}
                :required-sections   #{"reflection"}
                :no-apology?        true}}

   {:id        "intent-modifies-framing"
    :situation {:situation/text            "I need to force push to main"
                :situation/tool-descriptor "tool=Bash,input=git push --force origin main"
                :situation/contradictions  []}
    :intent    "user explicitly requested this force push"
    :expected  {:must-mention-cards  #{"never-force-push"}
                :must-flag-tensions  #{:principle-forbids}
                :required-sections   #{"Principle" "tensions" "reflection"}
                :no-apology?        true}}

   {:id        "multiple-tensions"
    :situation {:situation/text            "force pushing to main with pending contradictions"
                :situation/tool-descriptor "tool=Bash,input=git push --force origin main"
                :situation/contradictions  [{:contradiction/id       "c-bench-multi"
                                             :contradiction/category :self-contradictory
                                             :contradiction/between  [{:card/id "verify-before-delete"}]}]}
    :expected  {:must-mention-cards  #{"never-force-push"}
                :must-flag-tensions  #{:principle-forbids :contradiction-adjacent}
                :required-sections   #{"Principle" "tensions" "reflection"}
                :no-apology?        true}}

   {:id        "empty-card-pool"
    :cards     []
    :situation {:situation/text            "checking the weather forecast"
                :situation/tool-descriptor nil
                :situation/contradictions  []}
    :expected  {:must-mention-cards  #{}
                :must-flag-tensions  #{}
                :required-sections   #{"reflection"}
                :no-apology?        true}}])

;; ------------------------------------------------------------------
;; 2. Scoring
;; ------------------------------------------------------------------

(defn- heuristic-score
  "Score a consult response via pure heuristics — no LLM needed."
  [fixture response-text]
  (let [expected (:expected fixture)]
    {:sections-present? (boolean
                          (every? (fn [s] (str/includes? response-text (str "## " s)))
                                  (:required-sections expected #{})))
     :cards-mentioned?  (boolean
                          (every? (fn [cid] (str/includes? response-text cid))
                                  (:must-mention-cards expected #{})))
     :tensions-flagged? (boolean
                          (every? (fn [t] (str/includes? response-text (name t)))
                                  (:must-flag-tensions expected #{})))
     :no-apology?       (boolean
                          (not (re-find #"(?i)I am an AI|I apologize|as an AI"
                                        response-text)))}))

(defn- judge-consult-response
  "Call Sonnet to score a consult response 1-5. Returns
   {:judge-score int-or-nil :judge-rationale str :judge-cost-usd num}."
  [situation-text response-text]
  (let [prompt (str
                 "You are evaluating a consult response from an agent's identity system.\n\n"
                 "SITUATION: " situation-text "\n\n"
                 "RESPONSE:\n" response-text "\n\n"
                 "Score this response 1-5 on these criteria:\n"
                 "- Relevance: Does it address the situation with applicable identity cards?\n"
                 "- Tension identification: Does it correctly identify conflicts?\n"
                 "- Reflection quality: Is the reflection thoughtful, specific, and actionable?\n"
                 "- Tone: Direct, non-apologetic (no 'I am an AI' disclaimers)?\n\n"
                 "Return ONLY a JSON object: {\"score\": 1-5, \"rationale\": \"...\"}")
        result (transport/call prompt {:model-id     "claude-sonnet-4-6"
                                        :timeout-secs 30
                                        :output-toks  160})]
    (if (:ok? result)
      (let [parsed (claude/parse-json (:text result))]
        {:judge-score     (some-> (or (:score parsed) (get parsed "score"))
                                  long)
         :judge-rationale (or (:rationale parsed) (get parsed "rationale") "")
         :judge-cost-usd  (or (:cost-usd result) 0.0)})
      {:judge-score     nil
       :judge-rationale ""
       :judge-cost-usd  0.0})))

(defn- run-single-case-fn
  "Return a run-single-fn closure that captures the with-judge? flag."
  [with-judge?]
  (fn [fixture model-id timeout-secs]
    (let [situation (:situation fixture)
          cards     (if (contains? fixture :cards)
                      (:cards fixture)
                      scored-bench-cards)
          intent    (:intent fixture)

          ;; Pure consult query
          consult-result (consult/query cards situation config/default-config)
          view-md        (render/consult-view consult-result)
          prompt         (cli-consult/build-framed-prompt view-md intent)

          ;; LLM call
          result        (transport/call prompt {:model-id     model-id
                                                :timeout-secs timeout-secs
                                                :output-toks  400})
          response-text (or (:text result) "")
          parsed?       (and (:ok? result) (seq response-text))

          ;; Heuristic scoring
          heuristics (when parsed? (heuristic-score fixture response-text))

          ;; Optional LLM judge
          judge-result (when (and with-judge? parsed?)
                         (judge-consult-response
                           (:situation/text situation)
                           response-text))

          all-ok? (boolean
                    (and parsed?
                         (:sections-present? heuristics)
                         (:cards-mentioned? heuristics)
                         (:tensions-flagged? heuristics)
                         (:no-apology? heuristics)))

          total-cost (+ (or (:cost-usd result) 0.0)
                        (or (:judge-cost-usd judge-result) 0.0))]
      (cond-> {:case-id       (:id fixture)
               :parsed?       (boolean parsed?)
               :correct?      all-ok?
               :cost-usd      total-cost
               :latency-ms    (or (:latency-ms result) 0)
               :input-tokens  (or (:input-tokens result) 0)
               :output-tokens (or (:output-tokens result) 0)}
        heuristics              (merge (select-keys heuristics
                                                     [:sections-present? :cards-mentioned?
                                                      :tensions-flagged? :no-apology?]))
        (:judge-score judge-result) (assoc :judge-score (:judge-score judge-result))))))

;; ------------------------------------------------------------------
;; 3. Entry point
;; ------------------------------------------------------------------

(defn run
  "Entry point for `succession bench consult`."
  [project-root args]
  (let [opts        (bc/parse-args args "consult")
        models      (:models opts)
        cases       fixture-cases
        with-judge? (:with-judge? opts)
        _           (println (str "Consult Bench — " (count models) " model(s), "
                                  (count cases) " cases, "
                                  (:runs opts) " run(s) each"
                                  (when with-judge? " [+Sonnet judge]")))
        _           (println (str "Timeout: " (:timeout opts) "s per call\n"))
        run-fn      (run-single-case-fn with-judge?)
        results     (vec
                      (for [model-id models]
                        (do
                          (print (str "  " model-id " "))
                          (flush)
                          (let [r (bc/run-model-loop model-id cases run-fn
                                                      {:timeout-secs (:timeout opts)
                                                       :runs         (:runs opts)})]
                            (println)
                            r))))]
    (bc/print-results-table results)
    (bc/write-bench-results! project-root results :consult)
    0))
