(ns succession.identity.core
  "Entry dispatcher for the Succession identity-cycle system.

   Two kinds of entry points:

   - **Hooks** (wired into Claude Code via `.claude/settings.json`): receive
     the hook JSON on stdin, dispatch to `succession.identity.hook.*`, emit
     hookSpecificOutput JSON on stdout.

   - **CLI** (invoked by the user or by the agent via Bash): dispatch to
     `succession.identity.cli.*` by subcommand name.

   No domain logic lives here. This namespace is thin routing only.

   Usage:
     bb -m succession.identity.core hook session-start   < hook.json
     bb -m succession.identity.core consult \"situation\"
     bb -m succession.identity.core replay transcript.jsonl
     bb -m succession.identity.core config validate")

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
      "session-start"      ((requiring-resolve 'succession.identity.hook.session-start/run))
      "user-prompt-submit" ((requiring-resolve 'succession.identity.hook.user-prompt-submit/run))
      "pre-tool-use"       ((requiring-resolve 'succession.identity.hook.pre-tool-use/run))
      "post-tool-use"      ((requiring-resolve 'succession.identity.hook.post-tool-use/run))
      "stop"               ((requiring-resolve 'succession.identity.hook.stop/run))
      "pre-compact"        ((requiring-resolve 'succession.identity.hook.pre-compact/run))
      (unknown-command "hook" op))

    ;; CLI subcommands. Each cli namespace exposes a `run` fn taking
    ;; `[project-root args]`. The dispatcher resolves project-root once.
    "consult"
    ((requiring-resolve 'succession.identity.cli.consult/run)
     (project-root) (cons op rest-args))

    "replay"
    ((requiring-resolve 'succession.identity.cli.replay/run)
     (project-root) op)

    "config"
    ((requiring-resolve 'succession.identity.cli.config-validate/run)
     (project-root) (cons op rest-args))

    "install"
    ((requiring-resolve 'succession.identity.cli.install/run)
     (project-root) (cons op rest-args))

    "identity-diff"
    ((requiring-resolve 'succession.identity.cli.identity-diff/run)
     (project-root) (cons op rest-args))

    "import"
    ((requiring-resolve 'succession.identity.cli.import/run)
     (project-root) (cons op rest-args))

    (unknown-command "mode" mode)))
