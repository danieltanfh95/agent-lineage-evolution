(ns succession.extract
  "Retrospective rule extraction CLI.
   Analyzes past Claude Code transcripts and extracts behavioral rules.

   Usage: bb -m succession.extract [options] [transcript.jsonl]
     --session ID    Find transcript by session ID
     --last          Use most recent session
     --from-turn N   Extract from turn N onward
     --interactive   Drop into interactive exploration session
     --apply         Write extracted rules to disk (default: dry run)"
  (:require [succession.transcript :as transcript]
            [succession.yaml :as yaml]
            [succession.hooks.stop :as stop]
            [succession.resolve :as resolve]
            [succession.effectiveness :as eff]
            [babashka.fs :as fs]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.string :as str]))

(defn build-cli-extraction-prompt
  "Build extraction prompt for CLI use. Includes degradation_points and summary
   fields not present in the stop hook prompt."
  [transcript-text existing-rules]
  (str "You are a behavioral pattern extraction system analyzing a past conversation transcript. Identify:

1. CORRECTIONS — user told the agent to stop/change something
2. CONFIRMATIONS — user validated a non-obvious agent choice
3. PREFERENCES — how the user wants to work (tone, process, style)
4. DEGRADATION — points where agent behavior noticeably worsened (instruction drift, repetition, ignoring rules)

For each pattern, determine enforcement tier:
- \"mechanical\" — enforceable by blocking a tool/command pattern
- \"semantic\" — requires LLM judgment to enforce
- \"advisory\" — can only be reminded

For each pattern, classify into one of four knowledge categories:
- \"strategy\" — how the agent approaches problems (workflow patterns, methodologies)
- \"failure-inheritance\" — patterns of failure to avoid (anti-patterns, things that went wrong)
- \"relational-calibration\" — communication style adaptation (tone, verbosity, explanation depth)
- \"meta-cognition\" — which heuristics proved reliable vs. which sounded plausible but failed

EXISTING RULES (do not duplicate):
" (str/join "\n" existing-rules) "

TRANSCRIPT:
" transcript-text "

Output ONLY valid JSON (no markdown fencing):
{
  \"rules\": [
    {
      \"id\": \"kebab-case-id\",
      \"enforcement\": \"mechanical|semantic|advisory\",
      \"category\": \"strategy|failure-inheritance|relational-calibration|meta-cognition\",
      \"type\": \"correction|confirmation|preference\",
      \"scope\": \"global|project\",
      \"summary\": \"one-line rule statement\",
      \"evidence\": \"brief quote from transcript\",
      \"enforcement_directives\": [\"only for mechanical rules\"]
    }
  ],
  \"degradation_points\": [
    {
      \"approximate_turn\": 42,
      \"description\": \"Agent started ignoring rule X after this point\",
      \"possible_cause\": \"Context window filled with code output\"
    }
  ],
  \"summary\": \"Brief overall assessment of the session\"
}"))

(defn parse-extraction-result
  "Parse and validate the JSON result from the extraction LLM call.
   Strips markdown fencing, validates structure."
  [raw-output]
  (when raw-output
    (let [cleaned (-> raw-output
                      (str/replace #"(?m)^```json?\s*$" "")
                      (str/replace #"(?m)^```\s*$" "")
                      str/trim)]
      (try
        (let [parsed (json/parse-string cleaned true)]
          (when (:rules parsed) parsed))
        (catch Exception _ nil)))))

(defn display-results
  "Print extraction results to stdout in human-readable format."
  [result]
  (println)
  (println "=== Extraction Results ===")
  (println)

  ;; Summary
  (println (str "Summary: " (get result :summary "No summary")))
  (println)

  ;; Degradation points
  (let [deg-points (:degradation_points result)]
    (when (seq deg-points)
      (println "--- Degradation Points ---")
      (doseq [d deg-points]
        (println (str "  Turn ~" (:approximate_turn d) ": " (:description d)
                      " (" (:possible_cause d) ")")))
      (println)))

  ;; Rules
  (let [rules (:rules result)]
    (println (str "--- Extracted Rules (" (count rules) ") ---"))
    (println)
    (doseq [r rules]
      (println (str "[" (:enforcement r) "] " (:id r) ": " (:summary r)))
      (println (str "  Type: " (:type r) " | Category: " (:category r) " | Scope: " (:scope r)))
      (println (str "  Evidence: " (:evidence r)))
      (println))))

(defn write-rules!
  "Write extracted rules to disk. Returns count of rules written."
  [rules cwd session-id]
  (let [global-dir (str (System/getProperty "user.home") "/.succession/rules")
        project-dir (str cwd "/.succession/rules")
        written (atom 0)]
    (doseq [rule rules]
      (let [{:keys [id enforcement category type scope summary evidence
                    enforcement_directives]} rule
            target-dir (if (= "global" scope) global-dir project-dir)
            rule-file (str target-dir "/" id ".md")]
        (if (fs/exists? rule-file)
          (println (str "  SKIP (exists): " id))
          (do
            (fs/create-dirs target-dir)
            (let [directives-section (when (and (= "mechanical" enforcement)
                                               (seq enforcement_directives))
                                      (str "\n\n## Enforcement\n"
                                           (str/join "\n" (map #(str "- " %) enforcement_directives))))
                  rule-map {:id id
                            :scope (or scope "project")
                            :enforcement enforcement
                            :category (or category "strategy")
                            :type type
                            :source {:session (or session-id "retrospective")
                                     :timestamp (str (java.time.Instant/now))
                                     :evidence (or evidence "")}
                            :overrides []
                            :enabled true
                            :effectiveness {:times-followed 0
                                            :times-violated 0
                                            :times-overridden 0
                                            :last-evaluated nil}
                            :body (str (or summary "")
                                       (or directives-section ""))}]
              (yaml/write-rule-file rule-file rule-map)
              (swap! written inc)
              (println (str "  WROTE: " rule-file)))))))
    @written))

(defn run-interactive!
  "Launch interactive claude session with transcript loaded."
  [transcript-text]
  (println "Starting interactive session with transcript loaded...")
  (println "You can ask questions like:")
  (println "  - What corrections did the user make?")
  (println "  - Why did performance degrade after turn 50?")
  (println "  - Extract rules from turns 30-60")
  (println)
  (let [system-prompt (str "You are analyzing a Claude Code conversation transcript for behavioral pattern extraction. "
                           "The user will ask you questions about the transcript. Help them identify corrections, "
                           "preferences, degradation points, and extractable rules.\n\nTRANSCRIPT:\n"
                           transcript-text)]
    (process/shell {:in system-prompt} "claude" "--system-prompt" "-")))

(defn load-existing-rules
  "Load existing rule summaries from project + global dirs for deduplication."
  [cwd]
  (let [project-dir (str cwd "/.succession/rules")
        global-dir (str (System/getProperty "user.home") "/.succession/rules")]
    (vec
     (for [dir [project-dir global-dir]
           :when (fs/exists? dir)
           f (fs/glob dir "*.md")
           :let [rule (yaml/parse-rule-file (str f))]
           :when rule]
       (str "- " (:id rule) ": " (first (str/split-lines (or (:body rule) ""))))))))

(defn parse-args [args]
  (loop [args (seq args)
         opts {:from-turn 0 :interactive false :apply false}]
    (if-not args
      opts
      (let [[a & rest] args]
        (case a
          "--session"     (recur (next rest) (assoc opts :session-id (first rest)))
          "--last"        (recur rest (assoc opts :use-last true))
          "--from-turn"   (recur (next rest) (assoc opts :from-turn (parse-long (first rest))))
          "--interactive" (recur rest (assoc opts :interactive true))
          "--apply"       (recur rest (assoc opts :apply true))
          ("--help" "-h") (do (println "Usage: bb -m succession.extract [options] [transcript.jsonl]")
                              (println)
                              (println "Options:")
                              (println "  --session <id>      Find transcript by session ID")
                              (println "  --last              Use most recent session in current project")
                              (println "  --from-turn <N>     Extract from turn N onward")
                              (println "  --interactive       Drop into interactive exploration session")
                              (println "  --apply             Write extracted rules to disk (default: dry run)")
                              (println "  --help              Show this help")
                              (System/exit 0))
          ;; Default: treat as transcript path
          (recur rest (assoc opts :transcript-path a)))))))

(defn -main [& args]
  (let [{:keys [transcript-path session-id use-last from-turn interactive apply]} (parse-args args)
        cwd (System/getProperty "user.dir")

        ;; Resolve transcript
        resolved-path (cond
                        transcript-path transcript-path
                        session-id (transcript/find-transcript-by-session session-id)
                        use-last (transcript/find-latest-transcript cwd))]

    (when-not (and resolved-path (fs/exists? resolved-path))
      (binding [*out* *err*]
        (println (str "Error: " (if resolved-path
                                  "Transcript file not found"
                                  "No transcript specified")))
        (println "Usage: bb -m succession.extract [--last | --session <id> | <path>]"))
      (System/exit 1))

    (println (str "Analyzing: " resolved-path))
    (println)

    (let [total-turns (transcript/count-turns resolved-path)]
      (println (str "Total turns: " total-turns)))

    (let [transcript-text (transcript/read-transcript-text resolved-path
                            :from-turn from-turn :cap-bytes 200000)]
      (when (str/blank? transcript-text)
        (binding [*out* *err*]
          (println "Error: Could not extract messages from transcript"))
        (System/exit 1))

      (println (str "Transcript size: " (count transcript-text) " chars (capped at 200KB)"))
      (println)

      (if interactive
        (run-interactive! transcript-text)
        ;; Batch extraction
        (do
          (println "Running extraction...")
          (let [existing-rules (load-existing-rules cwd)
                prompt (build-cli-extraction-prompt transcript-text existing-rules)
                model-id (get stop/model-ids "sonnet" "claude-sonnet-4-6")
                raw-result (stop/call-claude prompt model-id 120)
                result (parse-extraction-result raw-result)]

            (if-not result
              (do
                (binding [*out* *err*]
                  (println "Error: Extraction returned invalid JSON"))
                (System/exit 1))
              (do
                (display-results result)

                (let [rules (:rules result)
                      rule-count (count rules)]
                  (if (and apply (pos? rule-count))
                    (do
                      (println "=== Writing Rules ===")
                      (let [written (write-rules! rules cwd "retrospective")]
                        (println)
                        (println (str "Written " written " rule(s). Run 'bb -m succession.core resolve' to compile."))))
                    (when (pos? rule-count)
                      (println "Dry run — use --apply to write these rules to disk."))))))))))))
