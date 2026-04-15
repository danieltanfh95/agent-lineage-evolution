(ns succession.core
  "Entry dispatcher for the Succession identity-cycle system.

   Two kinds of entry points:

   - **Hooks** (wired into Claude Code via `.claude/settings.json`): receive
     the hook JSON on stdin, dispatch to `succession.hook.*`, emit
     hookSpecificOutput JSON on stdout.

   - **CLI** (invoked by the user or by the agent via Bash): dispatch to
     `succession.cli.*` by subcommand name.

   No domain logic lives here. This namespace is thin routing only.

   Usage:
     succession hook session-start   < hook.json
     succession consult \"situation\"
     succession replay transcript.jsonl
     succession config validate"
  (:require [clojure.java.io :as io]))

(defn- unknown-command [kind name]
  (binding [*out* *err*]
    (println (format "unknown %s command: %s" kind name)))
  (System/exit 2))

(defn- find-git-root [dir]
  (loop [d (io/file dir)]
    (cond
      (nil? d) nil
      (.exists (io/file d ".git")) (.getPath d)
      :else (recur (.getParentFile d)))))

(defn- extract-root
  "Pull --root <path> out of args. Returns {:root path-or-nil :args cleaned-args}."
  [args]
  (let [v (vec args)
        i (.indexOf v "--root")]
    (if (and (>= i 0) (< (inc i) (count v)))
      {:root (get v (inc i))
       :args (into (subvec v 0 i) (subvec v (+ i 2)))}
      {:root nil :args v})))

(defn- project-root
  ([]        (project-root nil))
  ([explicit]
   (or explicit
       (System/getenv "CLAUDE_PROJECT_DIR")
       (find-git-root (or (System/getProperty "user.dir") "."))
       (or (System/getProperty "user.dir") "."))))

(defn- load-help-text []
  (or (some-> (io/resource "HELP.md") slurp)
      "succession — run 'succession --help' for usage (HELP.md resource not found)"))

(defn -main
  "Dispatch on first two args: mode (`hook` | cli subcommand-name) then op."
  [& raw-args]
  (let [{:keys [root args]} (extract-root raw-args)
        root                (project-root root)
        [mode op & rest-args] args]
    (when (or (nil? mode) (contains? #{"--help" "-h" "help"} mode))
      (println (load-help-text))
      (System/exit 0))
    (case mode
      "hook"
      (case op
        ;; Hook namespaces loaded lazily so a single hook firing doesn't pay
        ;; the cost of requiring all of them.
        "session-start"      ((requiring-resolve 'succession.hook.session-start/run))
        "user-prompt-submit" ((requiring-resolve 'succession.hook.user-prompt-submit/run))
        "pre-tool-use"       ((requiring-resolve 'succession.hook.pre-tool-use/run))
        "post-tool-use"      ((requiring-resolve 'succession.hook.post-tool-use/run))
        "stop"               ((requiring-resolve 'succession.hook.stop/run))
        "pre-compact"        ((requiring-resolve 'succession.hook.pre-compact/run))
        (unknown-command "hook" op))

      "--install-skill"
      (let [dest       (or (second (drop-while #(not= "--path" %) args))
                           ".claude/skills/succession/SKILL.md")
            frontmatter "---\nname: succession\ndescription: Persistent agent identity across Claude Code sessions\n---\n"
            content    (str frontmatter "\n" (load-help-text))]
        (io/make-parents dest)
        (spit dest content)
        (println "installed skill:" dest)
        (System/exit 0))

      ;; CLI subcommands. Each cli namespace exposes a `run` fn taking
      ;; `[project-root args]`. The dispatcher resolves project-root once.
      "consult"
      ((requiring-resolve 'succession.cli.consult/run)
       root (cons op rest-args))

      "replay"
      ((requiring-resolve 'succession.cli.replay/run)
       root op)

      "config"
      ((requiring-resolve 'succession.cli.config-validate/run)
       root (cons op rest-args))

      "install"
      ((requiring-resolve 'succession.cli.install/run)
       root (cons op rest-args))

      "identity-diff"
      ((requiring-resolve 'succession.cli.identity-diff/run)
       root (cons op rest-args))

      "show"
      (System/exit
        (or ((requiring-resolve 'succession.cli.show/run)
             root (cons op rest-args))
            0))

      "queue"
      (System/exit
        (or ((requiring-resolve 'succession.cli.queue/run)
             root (cons op rest-args))
            0))

      "import"
      ((requiring-resolve 'succession.cli.import/run)
       root (cons op rest-args))

      "bench"
      (System/exit
        (or ((requiring-resolve 'succession.cli.bench/run)
             root (cons op rest-args))
            0))

      "compact"
      (System/exit
        (or ((requiring-resolve 'succession.cli.compact/run)
             root rest-args)
            0))

      "staging"
      (System/exit
        (or ((requiring-resolve 'succession.cli.staging/run)
             root (cons op rest-args))
            0))

      "status"
      (System/exit
        (or ((requiring-resolve 'succession.cli.status/run)
             root rest-args)
            0))

      "observations"
      (System/exit
        (or ((requiring-resolve 'succession.cli.observations/run)
             root (cons op rest-args))
            0))

      "contradictions"
      (System/exit
        (or ((requiring-resolve 'succession.cli.contradictions/run)
             root (cons op rest-args))
            0))

      "archive"
      (System/exit
        (or ((requiring-resolve 'succession.cli.archive/run)
             root (cons op rest-args))
            0))

      "statusline"
      (System/exit
        (or ((requiring-resolve 'succession.cli.statusline/run)
             root)
            0))

      ;; Detached drain worker — post-tool-use and stop hooks spawn
      ;; `succession worker drain` after enqueueing. The invocation
      ;; acquires the worker lock, drains the queue, and exits on idle.
      "worker"
      (let [follow? (some #{"--follow" "-f"} rest-args)]
        (case op
          "drain" (System/exit
                    (or ((requiring-resolve 'succession.worker.drain/run!)
                         root)
                        0))
          "logs"  (let [log-file (str root "/.succession/staging/jobs/.worker.log")]
                    (if-not (.exists (java.io.File. log-file))
                      (do (binding [*out* *err*] (println "No worker log found:" log-file))
                          (System/exit 1))
                      (if follow?
                        (loop [offset 0]
                          (let [content (slurp log-file)
                                new-part (subs content (min offset (count content)))]
                            (when (pos? (count new-part)) (print new-part) (flush))
                            (Thread/sleep 200)
                            (recur (count content))))
                        (print (slurp log-file)))))
          (unknown-command "worker" op)))

      (unknown-command "mode" mode))))
