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
     bb -m succession.core hook session-start   < hook.json
     bb -m succession.core consult \"situation\"
     bb -m succession.core replay transcript.jsonl
     bb -m succession.core config validate")

(defn- unknown-command [kind name]
  (binding [*out* *err*]
    (println (format "unknown %s command: %s" kind name)))
  (System/exit 2))

(defn- project-root []
  (or (System/getProperty "user.dir") "."))

(defn -main
  "Dispatch on first two args: mode (`hook` | cli subcommand-name) then op."
  [& [mode op & rest-args]]
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

    ;; CLI subcommands. Each cli namespace exposes a `run` fn taking
    ;; `[project-root args]`. The dispatcher resolves project-root once.
    "consult"
    ((requiring-resolve 'succession.cli.consult/run)
     (project-root) (cons op rest-args))

    "replay"
    ((requiring-resolve 'succession.cli.replay/run)
     (project-root) op)

    "config"
    ((requiring-resolve 'succession.cli.config-validate/run)
     (project-root) (cons op rest-args))

    "install"
    ((requiring-resolve 'succession.cli.install/run)
     (project-root) (cons op rest-args))

    "identity-diff"
    ((requiring-resolve 'succession.cli.identity-diff/run)
     (project-root) (cons op rest-args))

    "show"
    (System/exit
      (or ((requiring-resolve 'succession.cli.show/run)
           (project-root) (cons op rest-args))
          0))

    "queue"
    (System/exit
      (or ((requiring-resolve 'succession.cli.queue/run)
           (project-root) (cons op rest-args))
          0))

    "import"
    ((requiring-resolve 'succession.cli.import/run)
     (project-root) (cons op rest-args))

    "bench"
    (System/exit
      (or ((requiring-resolve 'succession.cli.bench/run)
           (project-root) (cons op rest-args))
          0))

    "compact"
    (System/exit
      (or ((requiring-resolve 'succession.cli.compact/run)
           (project-root) rest-args)
          0))

    "staging"
    (System/exit
      (or ((requiring-resolve 'succession.cli.staging/run)
           (project-root) (cons op rest-args))
          0))

    "statusline"
    (System/exit
      (or ((requiring-resolve 'succession.cli.statusline/run)
           (project-root))
          0))

    ;; Detached drain worker — post-tool-use and stop hooks spawn
    ;; `bb succession worker drain` after enqueueing. The invocation
    ;; acquires the worker lock, drains the queue, and exits on idle.
    "worker"
    (case op
      "drain" (System/exit
                (or ((requiring-resolve 'succession.worker.drain/run!)
                     (project-root))
                    0))
      (unknown-command "worker" op))

    (unknown-command "mode" mode)))
