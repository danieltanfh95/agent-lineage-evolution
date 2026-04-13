(ns succession.cli.install
  "`bb -m succession.core install` — one-shot atomic setup.

   Writes every file Succession needs to run in a project:

     1. `.claude/skills/succession-consult/SKILL.md` — agent-facing
        discovery path. Tells the agent when to reach for
        `bb succession consult ...` and what the response means.
     2. `.succession/config.edn` — starter config via `config init`.
     3. `.succession/identity/promoted/{principle,rule,ethic}/` —
        empty promoted tier dirs so pre_compact has somewhere to rewrite.
     4. `.succession/observations/`, `.succession/staging/`,
        `.succession/archive/`, `.succession/contradictions/` — the
        store's live directories.
     5. `.claude/settings.local.json` hook entries for all six events,
        pointing at `bb -m succession.core hook <event>`.

   Idempotent: every step refuses to overwrite an existing file/entry.
   Running `install` on a project that already has some steps applied
   will only fill in the gaps. Reports what it did (or skipped) to
   stdout.

   Reference: `.plans/succession-identity-cycle.md` §Open Question 6
   (skill installation path)."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [succession.cli.config-validate :as config-cli]
            [succession.store.paths :as paths]))

;; ------------------------------------------------------------------
;; Skill content
;;
;; Matches plan §The skill near-verbatim. Kept inline so install is a
;; single-file CLI — no resource loading, no templating engine.
;; ------------------------------------------------------------------

(def skill-content
  "# succession-consult

Use when: you are uncertain whether to proceed, sense a contradiction
with prior behavior, or are about to do something you vaguely remember
being warned against.

Do not use when: the action is clearly routine, or you've consulted
in the last few turns about the same thing (it's logged — check your
recent bash output).

How: run `bb -m succession.core consult \"<one-sentence situation>\"`
with flags as needed. Read the response carefully. The response
organizes your identity into principle (inviolable), rule (default
behavior), and ethic (aspirational), and flags contradictions.

Respect what the reflection says. Principles are not negotiable
without explicit override. Rules are default behavior with justified
exceptions. Ethics are character niceties.

Consultation is logged but weight-neutral — you cannot \"look virtuous\"
by consulting more. It's a tool for resolving uncertainty, not a
performance metric.
")

;; ------------------------------------------------------------------
;; Hook entries — plan §Hook contract, event names match Claude Code's
;; settings.json schema.
;; ------------------------------------------------------------------

(def ^:private hook-events
  "Six events, their Claude Code event names, and the `bb -m` subcommand
   name. The event names match Claude Code's settings.json schema; the
   subcommand names match core.clj's hook dispatch."
  [["SessionStart"      "session-start"]
   ["UserPromptSubmit"  "user-prompt-submit"]
   ["PreToolUse"        "pre-tool-use"]
   ["PostToolUse"       "post-tool-use"]
   ["Stop"              "stop"]
   ["PreCompact"        "pre-compact"]])

(defn- bb-command
  "Hook command template. `src-path` is a string to place after `bb -cp`;
   callers pass either `$CLAUDE_PROJECT_DIR/bb/src` (most common — bb.edn
   lives under a `bb/` subdirectory) or `$CLAUDE_PROJECT_DIR/src` (bb.edn
   at project root)."
  [src-path sub]
  (str "bb -cp \"" src-path "\" -m succession.core hook " sub))

(defn- statusline-command
  "Statusline command template. Same `src-path` convention as `bb-command`."
  [src-path]
  (str "bb -cp \"" src-path "\" -m succession.core statusline"))

(defn build-hook-entries
  "Pure: produce the hooks section of a settings.json for these six
   events. Shape matches Claude Code's schema: each event name maps to
   a vector of `{:matcher ... :hooks [{:type \"command\" :command ...}]}`.

   `src-path` is the classpath root (relative to `$CLAUDE_PROJECT_DIR`)
   baked into each hook command — e.g. `$CLAUDE_PROJECT_DIR/bb/src`."
  [src-path]
  (into {}
        (map (fn [[event-name sub]]
               [event-name
                [{:matcher ""
                  :hooks   [{:type    "command"
                             :command (bb-command src-path sub)}]}]]))
        hook-events))

;; ------------------------------------------------------------------
;; Atomic-ish step runners
;;
;; Each step returns `{:step :ok|:skipped|:error :path ... :reason ...}`
;; so the top-level can print a report without re-raising partial
;; failures. `run!` collects the reports and exits 0 only if every step
;; was :ok or :skipped.
;; ------------------------------------------------------------------

(defn- ensure-file!
  "Write `content` to `path` only if the file does not already exist.
   Returns a result map."
  [path content step]
  (let [f (io/file path)]
    (if (.exists f)
      {:step step :status :skipped :path path :reason "exists"}
      (do (io/make-parents f)
          (spit f content)
          {:step step :status :ok :path path}))))

(defn- ensure-dir!
  [path step]
  (let [f (io/file path)]
    (if (.exists f)
      {:step step :status :skipped :path path :reason "exists"}
      (do (.mkdirs f)
          {:step step :status :ok :path path}))))

(defn install-skill!
  "Write `.claude/skills/succession-consult/SKILL.md`."
  [project-root]
  (ensure-file!
    (.getPath (io/file project-root ".claude" "skills" "succession-consult" "SKILL.md"))
    skill-content
    :skill))

(defn install-config!
  "Write the starter `.succession/config.edn` via the existing
   `config-cli` template. Falls through to `ensure-file!` semantics
   (never overwrites)."
  [project-root]
  (let [path (str (paths/root project-root) "/config.edn")
        f    (io/file path)]
    (if (.exists f)
      {:step :config :status :skipped :path path :reason "exists"}
      ;; Reuse the same template string config-cli/init! writes.
      (do (paths/ensure-dir! (paths/root project-root))
          (spit path (var-get #'config-cli/template-config))
          {:step :config :status :ok :path path}))))

(defn install-store-dirs!
  "Create the empty directory tree the store expects."
  [project-root]
  (let [paths-to-make
        [(paths/tier-dir project-root :principle)
         (paths/tier-dir project-root :rule)
         (paths/tier-dir project-root :ethic)
         (paths/observations-dir project-root)
         (paths/staging-dir project-root)
         (paths/contradictions-dir project-root)
         (paths/archive-dir project-root)
         (paths/judge-dir project-root)]]
    (mapv #(ensure-dir! % :store-dir) paths-to-make)))

(defn- read-json-if-exists
  [path]
  (let [f (io/file path)]
    (when (.exists f)
      (try (json/parse-string (slurp f) true)
           (catch Throwable _ nil)))))

(defn merge-hook-entries
  "Pure: merge the six Succession hook entries into an existing
   settings map. Does NOT overwrite existing entries for the same event
   — if the user already has another hook wired to SessionStart, we
   append ours to the vector rather than replacing theirs."
  [settings new-entries]
  (update settings :hooks
          (fn [existing]
            (reduce-kv
              (fn [acc event-name ours]
                (update acc event-name
                        (fn [cur]
                          (let [cur (or cur [])
                                already? (some (fn [entry]
                                                 (some (fn [h]
                                                         (= (:command h)
                                                            (get-in (first ours) [:hooks 0 :command])))
                                                       (:hooks entry)))
                                               cur)]
                            (if already?
                              cur
                              (into (vec cur) ours))))))
              (or existing {})
              new-entries))))

(defn- detect-src-path
  "Guess where the Succession source tree lives relative to
   `$CLAUDE_PROJECT_DIR`. Prefers `bb/src` (this project's layout) and
   falls back to `src` for single-tree projects."
  [project-root]
  (cond
    (.exists (io/file project-root "bb" "src" "succession" "core.clj"))
    "$CLAUDE_PROJECT_DIR/bb/src"

    (.exists (io/file project-root "src" "succession" "core.clj"))
    "$CLAUDE_PROJECT_DIR/src"

    :else
    "$CLAUDE_PROJECT_DIR/bb/src"))

(defn- merge-statusline
  "Pure: add the `statusLine` entry to a settings map if not already
   present. Does not overwrite an existing statusLine config."
  [settings src-path]
  (if (:statusLine settings)
    settings
    (assoc settings :statusLine {:type    "command"
                                 :command (statusline-command src-path)})))

(defn install-settings!
  "Update `.claude/settings.local.json` to wire all six Succession hooks
   and the statusline command. Creates the file if missing. Preserves any
   existing non-Succession hooks and any other top-level keys."
  [project-root]
  (let [path     (.getPath (io/file project-root ".claude" "settings.local.json"))
        src-path (detect-src-path project-root)
        existing (or (read-json-if-exists path) {})
        already? (some (fn [[_ entries]]
                         (some (fn [entry]
                                 (some (fn [h]
                                         (str/includes? (:command h "")
                                                        "succession.core hook"))
                                       (:hooks entry)))
                               entries))
                       (:hooks existing))
        merged   (-> (merge-hook-entries existing (build-hook-entries src-path))
                     (merge-statusline src-path))]
    (if already?
      {:step :settings :status :skipped :path path :reason "succession hooks already wired"}
      (do (io/make-parents (io/file path))
          (spit path (json/generate-string merged {:pretty true}))
          {:step :settings :status :ok :path path}))))

;; ------------------------------------------------------------------
;; Top-level runner
;; ------------------------------------------------------------------

(defn- print-report! [results]
  (doseq [r results]
    (let [tag (case (:status r) :ok "ok" :skipped "skip" :error "ERR")]
      (println (format "  [%s] %-12s %s%s"
                       tag
                       (name (:step r))
                       (:path r)
                       (if-let [reason (:reason r)]
                         (str "  (" reason ")")
                         ""))))))

(defn run
  [project-root _args]
  (println "installing succession at" project-root)
  (let [results (concat
                  [(install-skill!  project-root)
                   (install-config! project-root)]
                  (install-store-dirs! project-root)
                  [(install-settings! project-root)])
        ok?     (every? #(contains? #{:ok :skipped} (:status %)) results)]
    (print-report! results)
    (println)
    (println (if ok? "install complete." "install finished with errors."))
    (System/exit (if ok? 0 1))))
