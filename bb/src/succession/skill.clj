(ns succession.skill
  "Skill extraction CLI.
   Extracts replayable skill bundles (SKILL.md) from past Claude Code transcripts.

   Usage: bb -m succession.skill [options] [transcript.jsonl]
     --name NAME     Name for the extracted skill (auto-generated if omitted)
     --interactive   Drop into interactive exploration session
     --apply         Write SKILL.md to disk (default: dry run)
     --scope SCOPE   global or project (default: project)
     --from-turn N   Analyze from turn N onward
     --last          Use most recent session"
  (:require [succession.transcript :as transcript]
            [succession.hooks.stop :as stop]
            [babashka.fs :as fs]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.string :as str]))

(defn build-skill-extraction-prompt
  "Build the LLM prompt for skill extraction."
  [transcript-text]
  (str "You are a skill extraction system. Analyze this conversation transcript and extract a replayable SKILL — a bundle of behavioral patterns and domain knowledge that can be applied to similar tasks in the future.

A skill has four components:
1. CONTEXT — when this skill applies (trigger conditions, task type, file patterns)
2. STEPS — the workflow/behavioral pattern observed (what the agent did, in what order)
3. KNOWLEDGE — domain facts learned during the task (not behavioral rules)
4. RULES — corrections and preferences specific to this task type

TRANSCRIPT:
" transcript-text "

Output ONLY valid JSON (no markdown fencing):
{
  \"skill_name\": \"kebab-case name (e.g., express-api-debugging, react-component-refactor)\",
  \"description\": \"one-line description of what this skill does\",
  \"context\": {
    \"trigger\": \"when should this skill activate (e.g., 'when debugging Express API endpoints')\",
    \"file_patterns\": [\"optional glob patterns that indicate relevance (e.g., 'routes/*.ts')\"],
    \"keywords\": [\"words that suggest this skill applies\"]
  },
  \"steps\": [
    \"Step 1: what to do first\",
    \"Step 2: what to do next\"
  ],
  \"knowledge\": [
    \"Domain fact learned (e.g., 'Redis cache TTL defaults to 300s in this project')\"
  ],
  \"rules\": [
    {
      \"rule\": \"correction or preference specific to this task type\",
      \"enforcement\": \"mechanical|semantic|advisory\"
    }
  ],
  \"summary\": \"overall assessment of what was accomplished and how\"
}"))

(defn parse-skill-result
  "Parse and validate the JSON result from skill extraction."
  [raw-output]
  (when raw-output
    (let [cleaned (-> raw-output
                      (str/replace #"(?m)^```json?\s*$" "")
                      (str/replace #"(?m)^```\s*$" "")
                      str/trim)]
      (try
        (let [parsed (json/parse-string cleaned true)]
          (when (:skill_name parsed) parsed))
        (catch Exception _ nil)))))

(defn generate-skill-md
  "Generate SKILL.md content from the parsed extraction result."
  [result skill-name]
  (let [{:keys [description context steps knowledge rules]} result
        {:keys [trigger file_patterns keywords]} context]
    (str "---\n"
         "name: " skill-name "\n"
         "description: \"" description "\"\n"
         "---\n\n"
         "# " skill-name "\n\n"
         description "\n\n"
         "## When to Use\n\n"
         trigger "\n\n"
         (when (seq file_patterns)
           (str "File patterns: " (str/join ", " file_patterns) "\n"))
         (when (seq keywords)
           (str "Keywords: " (str/join ", " keywords) "\n"))
         "\n## Workflow\n\n"
         (str/join "\n" (map-indexed (fn [i step] (str (inc i) ". " step)) steps))
         "\n\n## Knowledge\n\n"
         (str/join "\n" (map #(str "- " %) knowledge))
         (when (seq rules)
           (str "\n\n## Rules\n\n"
                (str/join "\n" (map #(str "- [" (:enforcement %) "] " (:rule %)) rules))))
         "\n")))

(defn display-skill
  "Print extracted skill to stdout in human-readable format."
  [result skill-name]
  (println)
  (println "=== Extracted Skill ===")
  (println)
  (println (str "Name: " skill-name))
  (println (str "Description: " (:description result)))
  (println)

  (let [{:keys [trigger file_patterns keywords]} (:context result)]
    (println "--- Context ---")
    (println (str "  Trigger: " trigger))
    (when (seq file_patterns) (println (str "  Files: " (str/join ", " file_patterns))))
    (when (seq keywords) (println (str "  Keywords: " (str/join ", " keywords))))
    (println))

  (println "--- Steps ---")
  (doseq [step (:steps result)]
    (println (str "  " step)))
  (println)

  (println "--- Knowledge ---")
  (doseq [k (:knowledge result)]
    (println (str "  - " k)))
  (println)

  (let [rules (:rules result)]
    (when (seq rules)
      (println (str "--- Rules (" (count rules) ") ---"))
      (doseq [r rules]
        (println (str "  [" (:enforcement r) "] " (:rule r))))
      (println)))

  (when-let [summary (:summary result)]
    (println (str "Summary: " summary))
    (println)))

(defn write-skill!
  "Write SKILL.md to disk. Returns the path written."
  [result skill-name scope cwd]
  (let [target-dir (if (= "global" scope)
                     (str (System/getProperty "user.home") "/.succession/skills/" skill-name)
                     (str cwd "/.succession/skills/" skill-name))
        skill-file (str target-dir "/SKILL.md")]
    (fs/create-dirs target-dir)
    (spit skill-file (generate-skill-md result skill-name))
    (println (str "Wrote skill to: " skill-file))

    ;; Note about mechanical rules
    (when (some #(= "mechanical" (:enforcement %)) (:rules result))
      (println "  Note: Mechanical rules from skills should be added as separate rule files")
      (println "  Use 'succession add' to create them"))

    skill-file))

(defn run-interactive!
  "Launch interactive claude session for skill exploration."
  [transcript-text]
  (println "Starting interactive session for skill extraction...")
  (println "You can ask questions like:")
  (println "  - What task was being accomplished?")
  (println "  - What workflow pattern emerged?")
  (println "  - What domain knowledge was used?")
  (println "  - Extract a skill from turns 10-40")
  (println)
  (let [system-prompt (str "You are analyzing a Claude Code conversation transcript to extract a replayable SKILL. "
                           "A skill is a bundle of: (1) trigger conditions — when this skill applies, "
                           "(2) workflow steps — the behavioral pattern, (3) domain knowledge — facts learned, "
                           "(4) rules — corrections specific to this task type.\n\n"
                           "Help the user understand what happened in the session and extract a skill they can reuse.\n\n"
                           "TRANSCRIPT:\n" transcript-text)]
    (process/shell {:in system-prompt} "claude" "--system-prompt" "-")))

(defn parse-args [args]
  (loop [args (seq args)
         opts {:scope "project" :from-turn 0 :interactive false :apply false}]
    (if-not args
      opts
      (let [[a & rest] args]
        (case a
          "--name"        (recur (next rest) (assoc opts :skill-name (first rest)))
          "--interactive" (recur rest (assoc opts :interactive true))
          "--apply"       (recur rest (assoc opts :apply true))
          "--scope"       (recur (next rest) (assoc opts :scope (first rest)))
          "--from-turn"   (recur (next rest) (assoc opts :from-turn (parse-long (first rest))))
          "--last"        (recur rest (assoc opts :use-last true))
          ("--help" "-h") (do (println "Usage: bb -m succession.skill [options] [transcript.jsonl]")
                              (println)
                              (println "Options:")
                              (println "  --name <name>       Name for the extracted skill (auto-generated if omitted)")
                              (println "  --interactive       Drop into interactive exploration session")
                              (println "  --apply             Write SKILL.md to disk (default: dry run)")
                              (println "  --scope <scope>     global or project (default: project)")
                              (println "  --from-turn <N>     Analyze from turn N onward")
                              (println "  --last              Use most recent session")
                              (println "  --help              Show this help")
                              (System/exit 0))
          ;; Default: treat as transcript path
          (recur rest (assoc opts :transcript-path a)))))))

(defn -main [& args]
  (let [{:keys [transcript-path skill-name use-last from-turn interactive apply scope]}
        (parse-args args)
        cwd (System/getProperty "user.dir")

        ;; Resolve transcript
        resolved-path (cond
                        transcript-path transcript-path
                        use-last (transcript/find-latest-transcript cwd))]

    (when-not (and resolved-path (fs/exists? resolved-path))
      (binding [*out* *err*]
        (println "Error: No transcript specified or file not found")
        (println "Usage: bb -m succession.skill [--last | <path>]"))
      (System/exit 1))

    (println (str "Analyzing: " resolved-path))
    (println)

    (let [transcript-text (transcript/read-transcript-text resolved-path
                            :from-turn from-turn :cap-bytes 200000)]
      (when (str/blank? transcript-text)
        (binding [*out* *err*]
          (println "Error: Could not extract messages from transcript"))
        (System/exit 1))

      (println (str "Transcript size: " (count transcript-text) " chars"))
      (println)

      (if interactive
        (run-interactive! transcript-text)
        ;; Batch extraction
        (do
          (println "Extracting skill...")
          (let [prompt (build-skill-extraction-prompt transcript-text)
                model-id (get stop/model-ids "sonnet" "claude-sonnet-4-6")
                raw-result (stop/call-claude prompt model-id 120)
                result (parse-skill-result raw-result)]

            (if-not result
              (do
                (binding [*out* *err*]
                  (println "Error: Extraction returned invalid JSON"))
                (System/exit 1))
              (let [final-name (or skill-name (:skill_name result))]
                (display-skill result final-name)

                (if apply
                  (write-skill! result final-name scope cwd)
                  (do
                    (println "--- Generated SKILL.md (preview) ---")
                    (println)
                    (println (generate-skill-md result final-name))
                    (println "Dry run — use --apply to write to disk.")))))))))))
