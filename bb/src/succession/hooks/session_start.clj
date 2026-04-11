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

(defn walk-skill-dirs
  "Walk a root dir recursively and return every directory that contains a
   SKILL.md file. Supports nested skill layouts like
   skills/soul/replsh/SKILL.md."
  [root]
  (when (and root (fs/exists? root) (fs/directory? root))
    (->> (file-seq (fs/file root))
         (filter #(and (.isFile %) (= "SKILL.md" (.getName %))))
         (map #(.getParentFile %))
         (distinct))))

(defn copy-skill-dir!
  "Copy a single skill directory (containing SKILL.md) into
   .claude/skills/<name>/ using its leaf directory name as the skill name.
   Returns the skill name, or nil if already loaded."
  [loaded skill-dir cwd]
  (let [skill-name (.getName skill-dir)
        skill-file (str skill-dir "/SKILL.md")]
    (when (and (fs/exists? skill-file)
               (not (contains? loaded skill-name)))
      (let [target-dir (str cwd "/.claude/skills/" skill-name)]
        (fs/create-dirs target-dir)
        (fs/copy skill-file (str target-dir "/SKILL.md") {:replace-existing true}))
      skill-name)))

(defn load-skills-from
  "Copy every skill under skills-dir into .claude/skills/ for Claude Code
   to pick up. Returns the updated loaded set."
  [loaded skills-dir cwd]
  (reduce (fn [acc dir]
            (if-let [name (copy-skill-dir! acc dir cwd)]
              (conj acc name)
              acc))
          loaded
          (walk-skill-dirs skills-dir)))

(defn -main []
  (try
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
                  (swap! parts conj (slurp advisory-file)))

              digest-file (str compiled-dir "/active-rules-digest.md")
              _ (when (and (fs/exists? digest-file)
                           (pos? (fs/size digest-file)))
                  (swap! parts conj (str "\n--- SUCCESSION: ACTIVE RULES DIGEST ---\n"
                                         (slurp digest-file))))

              semantic-file (str compiled-dir "/semantic-rules.md")
              _ (when (and (fs/exists? semantic-file)
                           (pos? (fs/size semantic-file)))
                  (swap! parts conj (str "\n--- SUCCESSION: SEMANTIC RULES (enforced on tool calls) ---\n"
                                         (slurp semantic-file))))

              ;; Phase 3: Load skills — project .succession/skills/,
              ;; global ~/.succession/skills/, and repo-local skills/
              loaded-skills (-> #{}
                                (load-skills-from (str succession-dir "/skills") cwd)
                                (load-skills-from (str global-dir "/skills") cwd)
                                (load-skills-from (str cwd "/skills") cwd))

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
                        :additionalContext assembled}}))))))
    (catch Exception e
      (binding [*out* *err*]
        (println (str "Succession session_start hook error: " (.getMessage e))))
      (System/exit 2))))

(when (= *file* (System/getProperty "babashka.file"))
  (-main))
