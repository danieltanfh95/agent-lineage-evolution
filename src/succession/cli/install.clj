(ns succession.cli.install
  "`succession install` — one-shot atomic setup.

   Writes every file Succession needs to run in a project:

     1. `.claude/skills/succession-consult/SKILL.md` — agent-facing
        discovery path. Tells the agent when to reach for
        `succession consult ...` and what the response means.
     2. `.succession/config.edn` — starter config via `config init`.
     3. `.succession/identity/promoted/{principle,rule,ethic}/` —
        empty promoted tier dirs so pre_compact has somewhere to rewrite.
     4. `.succession/observations/`, `.succession/staging/`,
        `.succession/archive/`, `.succession/contradictions/` — the
        store's live directories.
     5. `.claude/settings.local.json` hook entries for all six events,
        using `succession hook <event>` (requires bbin-installed binary).
        Pass `--global` to write to `~/.claude/settings.json` instead.

   Idempotent: every step refuses to overwrite an existing file/entry.
   Running `install` on a project that already has some steps applied
   will only fill in the gaps. Reports what it did (or skipped) to
   stdout.

   Reference: `.plans/succession-identity-cycle.md` §Open Question 6
   (skill installation path)."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [succession.cli.config-validate :as config-cli]
            [succession.store.cards :as cards]
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

How: run `succession consult \"<one-sentence situation>\"`
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

(defn- hook-command [sub]
  (str "succession hook " sub))

(defn- statusline-command []
  "succession statusline")

(defn build-hook-entries
  "Pure: produce the hooks section of a settings.json for these six
   events. Shape matches Claude Code's schema: each event name maps to
   a vector of `{:matcher ... :hooks [{:type \"command\" :command ...}]}`.

   Keys are keywords so they match what `json/parse-string` with `true`
   produces when reading an existing settings file — required for
   `merge-hook-entries` to find existing entries by key."
  []
  (into {}
        (map (fn [[event-name sub]]
               [(keyword event-name)
                [{:matcher ""
                  :hooks   [{:type    "command"
                             :command (hook-command sub)}]}]]))
        hook-events))

;; ------------------------------------------------------------------
;; Starter card resources
;;
;; Classpath resource dirs are not enumerable, so we keep an explicit
;; list. Each entry is a classpath-relative resource path; the card's
;; tier and id are read from the file's own frontmatter.
;; ------------------------------------------------------------------

(def ^:private starter-card-resources
  ["starter-cards/verify-via-repl.md"
   "starter-cards/data-first-design.md"
   "starter-cards/infinite-context.md"
   "starter-cards/judge-conscience-framing.md"])

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

(defn- merge-statusline
  "Pure: add the `statusLine` entry to a settings map if not already
   present. Does not overwrite an existing statusLine config."
  [settings]
  (if (:statusLine settings)
    settings
    (assoc settings :statusLine {:type    "command"
                                 :command (statusline-command)})))

(defn install-settings!
  "Update a Claude Code settings JSON file to wire all six Succession
   hooks and the statusline command. Creates the file if missing.
   Preserves any existing non-Succession hooks and any other top-level
   keys.

   `settings-path` is the absolute path to the target settings file —
   typically `.claude/settings.local.json` for per-project installs or
   `~/.claude/settings.json` for global installs (`--global`)."
  [settings-path]
  (let [existing (or (read-json-if-exists settings-path) {})
        merged   (-> (merge-hook-entries existing (build-hook-entries))
                     (merge-statusline))]
    (if (= merged existing)
      {:step :settings :status :skipped :path settings-path :reason "succession hooks already wired"}
      (do (io/make-parents (io/file settings-path))
          (spit settings-path (json/generate-string merged {:pretty true}))
          {:step :settings :status :ok :path settings-path}))))

(defn install-starter-pack!
  "Copy bundled starter cards from the classpath into the project's
   identity store. Each card is written to the tier directory declared
   in its own frontmatter. Idempotent — skips cards whose destination
   file already exists."
  [project-root]
  (mapv (fn [resource-name]
          (if-let [url (io/resource resource-name)]
            (let [card (cards/read-card url)
                  dest (paths/card-file project-root (:card/tier card) (:card/id card))]
              (if (.exists (io/file dest))
                {:step :starter-card :status :skipped :path dest :reason "exists"}
                (do (cards/write-card! project-root card)
                    {:step :starter-card :status :ok :path dest})))
            {:step :starter-card :status :error
             :path resource-name :reason "resource not found on classpath"}))
        starter-card-resources))

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
  [project-root args]
  (let [;; --local is an explicit alias for the default per-project mode
        global?       (some #{"--global"} args)
        starter-pack? (some #{"--starter-pack"} args)
        settings-path (if global?
                        (str (System/getProperty "user.home") "/.claude/settings.json")
                        (.getPath (io/file project-root ".claude" "settings.local.json")))]
    (println (if global?
               (str "installing hooks globally at " settings-path)
               (str "installing succession at " project-root)))
    (let [results (concat
                    (when-not global?
                      [(install-skill!  project-root)
                       (install-config! project-root)])
                    (when-not global?
                      (install-store-dirs! project-root))
                    [(install-settings! settings-path)]
                    (when starter-pack?
                      (install-starter-pack! project-root)))
          ok?     (every? #(contains? #{:ok :skipped} (:status %)) results)
      results (if (and ok? starter-pack? (not global?))
                (do (cards/materialize-promoted! project-root)
                    (conj (vec results) {:step :snapshot :status :ok
                                         :path (str (paths/root project-root) "/promoted.edn")}))
                results)]
      (print-report! results)
      (println)
      (println (if ok? "install complete." "install finished with errors."))
      (when (and ok? (not global?))
        (println)
        (println "Next steps:")
        (println "  1. Open a Claude Code session — hooks fire automatically, no manual steps.")
        (println "  2. Run 'succession show' to see your identity cards accumulate.")
        (println "  3. Run 'succession queue status' to check the async judge queue.")
        (println "  4. Run 'succession config validate' to verify your config."))
      (System/exit (if ok? 0 1)))))
