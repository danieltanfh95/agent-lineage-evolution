#!/usr/bin/env bb
(ns succession.hooks.session-start
  "SessionStart hook: compile rules via cascade resolution and inject
   advisory rules + skills as additionalContext.

   Input: JSON on stdin with session_id, cwd
   Output: JSON with hookSpecificOutput.additionalContext"
  (:require [cheshire.core :as json]
            [babashka.fs :as fs]
            [clojure.string :as str]
            [succession.resolve :as resolve]
            [succession.activity :as activity]))

(defn load-skills-from
  "Copy skills from a skills directory into .claude/skills/ for Claude Code to pick up.
   Returns set of loaded skill names."
  [loaded skills-dir cwd]
  (if (fs/exists? skills-dir)
    (reduce (fn [loaded skill-dir]
              (let [skill-name (str (fs/file-name skill-dir))
                    skill-file (str skill-dir "/SKILL.md")]
                (if (and (fs/directory? skill-dir)
                         (fs/exists? skill-file)
                         (not (contains? loaded skill-name)))
                  (do
                    (let [target-dir (str cwd "/.claude/skills/" skill-name)]
                      (fs/create-dirs target-dir)
                      (fs/copy skill-file (str target-dir "/SKILL.md") {:replace-existing true}))
                    (conj loaded skill-name))
                  loaded)))
            loaded
            (fs/list-dir skills-dir))
    loaded))

(defn -main []
  (let [input (json/parse-string (slurp *in*) true)
        {:keys [cwd session_id]} input
        global-dir (str (System/getProperty "user.home") "/.succession")
        succession-dir (str cwd "/.succession")]

    ;; Bail if no .succession directory and no global rules
    (when (or (fs/exists? succession-dir)
              (fs/exists? (str global-dir "/rules")))

      ;; Ensure directories exist
      (fs/create-dirs (str succession-dir "/rules"))
      (fs/create-dirs (str succession-dir "/compiled"))
      (fs/create-dirs (str succession-dir "/log"))

      ;; Log rotation
      (try (activity/rotate-log-if-needed! cwd) (catch Exception _))

      ;; Phase 1: Compile rules
      (let [compile-result (try (resolve/resolve-and-compile! cwd) (catch Exception _ nil))

            ;; Phase 2: Assemble additionalContext
            compiled-dir (str succession-dir "/compiled")
            parts (atom [])

            advisory-file (str compiled-dir "/advisory-summary.md")
            _ (when (and (fs/exists? advisory-file)
                         (pos? (fs/size advisory-file)))
                (swap! parts conj (str "\n--- SUCCESSION: ACTIVE RULES ---\n"
                                       (slurp advisory-file))))

            semantic-file (str compiled-dir "/semantic-rules.md")
            _ (when (and (fs/exists? semantic-file)
                         (pos? (fs/size semantic-file)))
                (swap! parts conj (str "\n--- SUCCESSION: SEMANTIC RULES (enforced on tool calls) ---\n"
                                       (slurp semantic-file))))

            ;; Phase 3: Load skills
            loaded-skills (-> #{}
                              (load-skills-from (str succession-dir "/skills") cwd)
                              (load-skills-from (str global-dir "/skills") cwd))

            ;; Phase 4: Resolution note
            _ (swap! parts conj
                     (str "\n--- SUCCESSION: RULE RESOLUTION ---\n"
                          "Rules are resolved by cascade: project-level (.succession/rules/) overrides global (~/.succession/rules/).\n"
                          "Rules with explicit 'overrides' fields cancel the referenced rules.\n"
                          "Higher priority numbers win among rules at the same scope level."))

            assembled (str/join "\n" @parts)]

        ;; Log session_start event
        (try
          (activity/log-activity-event! "session_start" cwd session_id
            {:mechanical_rules (get compile-result :mechanical 0)
             :skills_loaded (str/join "|" loaded-skills)})
          (catch Exception _))

        (when (seq assembled)
          (println (json/generate-string
                    {:hookSpecificOutput
                     {:hookEventName "SessionStart"
                      :additionalContext assembled}})))))))

(when (= *file* (System/getProperty "babashka.file"))
  (-main))
