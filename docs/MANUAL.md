# Succession Manual

Exhaustive command and hook reference. For the architectural context see
[ARCHITECTURE.md](ARCHITECTURE.md); for per-hook stdin/stdout shapes see
[HOOKS.md](HOOKS.md).

## Synopsis

```
bb -m succession.core <command> [args…]
```

Two kinds of invocation:

- `bb -m succession.core hook <event>` — wired into Claude Code via
  `.claude/settings.local.json`. Reads one JSON object on stdin, emits zero
  or one on stdout.
- `bb -m succession.core <cli-subcommand>` — invoked by the user or the
  agent via Bash. Prints to stdout, exits with a meaningful status code.

Every command loads its effective config via
`succession.config/load-config <project-root>`, which deep-merges
`.succession/config.edn` over `default-config`. See [Config file
reference](#config-file-reference).

## Commands

### consult

Reflective second-opinion query against the current identity. The agent
invokes it via Bash when uncertain; humans run it to see what the agent
would see.

```
bb -m succession.core consult "<situation>" [flags…]
```

| Flag | Arg | Description |
|------|-----|-------------|
| `--intent` | text | What the agent is about to do |
| `--tool-name` | name | Upcoming tool name (e.g. `Bash`) — feeds fingerprint match |
| `--tool-input` | edn | Upcoming tool input — feeds fingerprint match |
| `--recent-context` | text | Free-form situational summary |
| `--category` | keyword | Filter pool to one knowledge category |
| `--tier` | keyword | Filter pool to one tier (`principle`/`rule`/`ethic`) |
| `--exclude` | csv | Comma-separated card ids to exclude from the pool |
| `--session` | id | Also consider staged deltas from this session |
| `--dry-run` | — | Skip the LLM call; print the consult-view markdown only |
| `-h`/`--help` | — | Print usage |

Positional args are concatenated into the situation string. The
situation is required.

**Examples:**

```bash
bb -m succession.core consult "about to force-push to main"
bb -m succession.core consult "rewriting a test file" --tool-name Edit --intent "fix assertion"
bb -m succession.core consult "ship the migration" --tier principle --dry-run
```

Output: the LLM reflection (four tier sections + tensions + reflection).
Every card that made it into the candidate pool is logged as a
`:consulted` observation — weight-neutral but visible in the audit
trail. Exit 0 on success, 1 on LLM error or missing situation.

Reference: `bb/src/succession/cli/consult.clj`.

### replay

Walk a Claude Code transcript JSONL through the identity cycle in a
sandbox directory. Used to catch shape regressions across the
store/domain/LLM boundary without actually calling the judge.

```
bb -m succession.core replay <transcript.jsonl>
```

No flags — positional argument only. The harness:

1. Resets `.succession-next/` under the current project root.
2. Parses the transcript, extracting every `tool_use` entry.
3. Writes one synthetic `:confirmed` observation per tool call,
   attributed to a placeholder card id (`replay-placeholder-<tool>`).
4. Stages one `:observe-card` delta per tool call.
5. Rematerializes staging and runs `reconcile/detect-all`.
6. Prints a stats summary (tool distribution, sessions, contradictions).

Exit 0 on success, 1 on any error (transcript missing, parse failure).

Reference: `bb/src/succession/cli/replay.clj`.

### config

Three subcommands for the `.succession/config.edn` file.

```
bb -m succession.core config <validate | show | init>
```

- **`validate`** — read the effective config (defaults + overlay) and
  run `config/validate`. Exits 0 if clean, 1 if any `{:path :problem}`
  is reported. Prints each problem.
- **`show`** — pretty-print the effective config to stdout. Exit 0.
- **`init`** — write a starter `.succession/config.edn` populated with
  the defaults and inline comments. Refuses to overwrite an existing
  file (exit 1). Idempotent for fresh installs.

Reference: `bb/src/succession/cli/config_validate.clj`.

### install

One-shot atomic setup of everything Succession needs in a project.

```
bb -m succession.core install
```

Writes (all idempotent; existing files are left alone):

- `.claude/skills/succession-consult/SKILL.md` — agent-facing
  discovery path for the consult command.
- `.succession/config.edn` — starter config via the same template
  `config init` uses.
- `.succession/identity/promoted/{principle,rule,ethic}/` — empty
  tier directories so PreCompact has somewhere to rewrite.
- `.succession/observations/`, `.succession/staging/`,
  `.succession/archive/`, `.succession/contradictions/`,
  `.succession/judge/` — live store directories.
- `.claude/settings.local.json` — hook entries for all six events,
  each pointing at `bb -cp "$CLAUDE_PROJECT_DIR/bb/src" -m
  succession.core hook <event>`. Existing non-Succession hooks are
  preserved.

`install` auto-detects the classpath root: `bb/src/succession/core.clj`
wins over `src/succession/core.clj`. Prints a per-step report (`ok` /
`skip` / `ERR`) and exits 0 if every step is `:ok` or `:skipped`.

Reference: `bb/src/succession/cli/install.clj`.

### identity-diff

Compare two archived identity snapshots. Every PreCompact writes an
archive under `.succession/archive/{ts}/promoted/…` before rewriting
the live tree; `identity-diff` shows what changed.

```
bb -m succession.core identity-diff <ts1> <ts2|current>
```

`ts1` and `ts2` are archive directory names (timestamp strings). `ts2`
may be the literal `current` to diff the most recent archive against
the live promoted tree.

Reports four change categories:

- `added`     — cards present in `ts2`, absent in `ts1`
- `removed`   — cards present in `ts1`, absent in `ts2`
- `retiered`  — same id, different `:card/tier`
- `rewritten` — same id, same tier, different `:card/text`

Exit 0 on successful diff (including "no differences"), 2 on missing
args.

Reference: `bb/src/succession/cli/identity_diff.clj`.

### import

One-shot migration of an old `.succession/rules/*.md` YAML-frontmatter
directory into the new card store.

```
bb -m succession.core import <old-rules-dir>
```

Each old rule becomes a `:create-card` delta. All imported cards land
in `:ethic` — they must earn their way up through real observations.
`effectiveness` counters in the old frontmatter are discarded. After
staging deltas the harness calls `pre-compact/promote!` directly so
the imported cards land in `identity/promoted/ethic/` immediately.

Idempotent at the delta level: re-running writes two deltas per rule,
but the second promotion rewrites the same card id with the same text.

Exit 0 on success, 1 if no files parsed or directory missing, 2 on
missing argument.

Reference: `bb/src/succession/cli/import.clj`.

### hook &lt;event&gt;

Invoked by Claude Code via `.claude/settings.local.json`. Reads one JSON
object on stdin, runs the event handler, emits zero or one JSON object
on stdout, never throws.

```
bb -m succession.core hook <event>
```

Six events, matching Claude Code's schema:

| CLI arg | Claude Code event | Handler ns |
|---------|-------------------|------------|
| `session-start`      | `SessionStart`     | `succession.hook.session-start` |
| `user-prompt-submit` | `UserPromptSubmit` | `succession.hook.user-prompt-submit` |
| `pre-tool-use`       | `PreToolUse`       | `succession.hook.pre-tool-use` |
| `post-tool-use`      | `PostToolUse`      | `succession.hook.post-tool-use` |
| `stop`               | `Stop`             | `succession.hook.stop` |
| `pre-compact`        | `PreCompact`       | `succession.hook.pre-compact` |

Full per-hook stdin/stdout contracts live in [HOOKS.md](HOOKS.md).

## Hook invocation contract

Every hook entry follows the same skeleton (see `succession.hook.common`):

```clojure
(defn run []
  (try
    (let [input        (common/read-input)           ; parse stdin JSON
          project-root (common/project-root input)   ; derive from :cwd
          cfg          (common/load-config input)]
      …)
    (catch Throwable t
      (binding [*out* *err*]
        (println "succession <hook> error:" (.getMessage t)))))
  nil)
```

Stdin is a single JSON object matching Claude Code's hook payload schema
for that event. Stdout is a single JSON object of the form:

```json
{
  "hookSpecificOutput": {
    "hookEventName": "PostToolUse",
    "additionalContext": "…markdown reminder…"
  }
}
```

— or nothing at all, if the hook has nothing to say. Hooks must be
fail-safe: any throwable is caught and logged to stderr, and the hook
returns nil. A hook must never propagate an exception to Claude Code's
harness.

## Config file reference

Every tunable lives in `.succession/config.edn`, deep-merged over
`succession.config/default-config`. Only override the keys you disagree
with.

### Version

| Key | Default | Type | Description |
|-----|---------|------|-------------|
| `:succession/config-version` | `1` | int | Schema version for future migrations |

### Weight formula

| Key | Default | Type | Effect |
|-----|---------|------|--------|
| `:weight/freq-cap` | `4.0` | number | Ceiling on `sqrt(distinct-sessions)` |
| `:weight/span-exponent` | `1.5` | number | `(1+log(1+span_days))^exp` |
| `:weight/within-session-penalty` | `0.5` | number | Multiplier when `gap-crossings = 0` |
| `:weight/decay-half-life-days` | `180` | positive number | Exponential decay half-life |
| `:weight/violation-penalty-rate` | `0.5` | number | Multiplier on `base * violation_rate` |
| `:weight/gap-threshold-sessions` | `1` | int | Min dormant sessions to count as a gap crossing |

See [ARCHITECTURE.md §Weight formula](ARCHITECTURE.md#weight-formula).

### Tier rules

```clojure
:tier/rules
{:principle {:enter {:min-weight 30.0 :max-violation-rate 0.0 :min-gap-crossings 5}
             :exit  {:max-weight 20.0 :min-violation-rate 0.1}}
 :rule      {:enter {:min-weight 5.0  :max-violation-rate 0.3 :min-gap-crossings 1}
             :exit  {:max-weight 3.0  :min-violation-rate 0.5}}
 :ethic     {:enter {} :exit {:archive-below-weight 0.5}}}
```

Enter conditions are AND (all must hold); exit conditions are OR (any
trips demotion). `:ethic`'s `:archive-below-weight` is the floor —
cards below it drop out of the promoted tree.

### Salience profile

| Key | Default | Effect |
|-----|---------|--------|
| `:salience/profile :feature-weights :tier-weight` | `3.0` | Weight of the card's declared tier |
| `:salience/profile :feature-weights :tag-match` | `2.0` | Bonus for tag overlap with situation |
| `:salience/profile :feature-weights :fingerprint` | `4.0` | Bonus when fingerprint substring-matches the tool descriptor |
| `:salience/profile :feature-weights :recency` | `0.5` | Bonus for freshness (0..1) |
| `:salience/profile :feature-weights :weight` | `1.0` | Bonus proportional to computed weight |
| `:salience/profile :top-k` | `3` | Max cards surfaced in a reminder |
| `:salience/profile :byte-cap` | `400` | Max bytes in the rendered reminder |

### Refresh gate (Finding 1)

```clojure
:refresh/gate
{:integration-gap-turns 2
 :cap-per-session       5
 :byte-threshold        200
 :cold-start-skip-turns 1}
```

These values are imported unchanged from the pytest-5103 replay that
established the 18-0 result. Do not re-derive without a new experiment.

### Consult advisory

```clojure
:consult/advisory
{:every-n-turns              8
 :on-contradiction-adjacency true}
```

Cadence of the inline "you can consult" reminder piggybacked on
PostToolUse refresh.

### Escalation

```clojure
:escalation/sustained-violation {:min-rate 0.1 :min-sessions 3}
:escalation/drift-alarm         {:contradictions-per-n-tool-calls 3
                                 :n-tool-calls 20}
```

### LLM models

| Key | Default model | Default timeout (s) | Notes |
|-----|---------------|---------------------|-------|
| `:reconcile/llm :model` | `claude-sonnet-4-6` | 60 | `:auto-apply-confidence` 0.8 |
| `:judge/llm :model` | `claude-sonnet-4-6` | 30 | async PostToolUse verdict lane |
| `:consult/llm :model` | `claude-sonnet-4-6` | 60 | `bb consult` reflection |

### Correction detection

```clojure
:correction/patterns
["(?i)\\bno,?\\s+(?:use|do|try)\\b"
 "(?i)\\bstop\\s+\\w+ing\\b"
 "(?i)\\bdon'?t\\s+\\w+"
 "(?i)\\bactually[,.]?\\s"
 "(?i)\\bthat'?s\\s+(?:wrong|not\\s+right)\\b"
 "(?i)\\binstead[,.]?\\s"
 "(?i)\\bnot\\s+that\\b"]
```

Patterns are deliberately broad — false positives are filtered
downstream by the extract LLM. A match writes a `:mark-contradiction`
delta to staging with `:source :user-correction`.

### Retention

| Key | Default | Effect |
|-----|---------|--------|
| `:retention/raw-observations-sessions` | `50` | Retain the last N sessions of raw observations |

### Knowledge categories

```clojure
:card/categories [:strategy :failure-inheritance :relational-calibration :meta-cognition]
```

Closed set. Cards outside this set are a schema violation. See
whitepaper §3.3.3 for the taxonomy.

## Disk layout

All paths under `<project-root>/.succession/`. Source of truth:
`bb/src/succession/store/paths.clj`.

```
.succession/
├── config.edn                                 starter config (written by install / config init)
├── promoted.edn                               fast-read materialized card snapshot
├── promote.lock                               advisory file lock (PreCompact)
├── escalations.jsonl                          user-facing escalations
├── identity/
│   └── promoted/
│       ├── principle/{card-id}.md             canonical identity cards
│       ├── rule/{card-id}.md
│       └── ethic/{card-id}.md
├── staging/
│   ├── {session-id}/
│   │   ├── deltas.jsonl                       intra-session append-only
│   │   ├── snapshot.edn                       materialized staging view
│   │   └── contradictions.jsonl               pure-detector output for this session
│   └── jobs/                                  async job queue (drain worker)
│       ├── {ts}-{uuid}.json                   pending job
│       ├── .inflight/{ts}-{uuid}.json         claimed by a worker
│       ├── dead/{ts}-{uuid}.json              handler threw
│       ├── dead/{ts}-{uuid}.error.edn         sibling error snapshot
│       └── .worker.lock                       at-most-one drain worker
├── observations/
│   └── {session-id}/
│       └── {ts}-{uuid}.edn                    one file per observation
├── contradictions/
│   └── {contradiction-id}.edn                 canonical contradiction records
├── judge/
│   └── {session-id}/
│       └── verdicts.jsonl                     raw judge verdicts
└── archive/
    └── {pre-compact-ts}/
        └── promoted/…                         pre-promotion snapshots
```

Transient state — the refresh gate's per-session counters — lives under
`/tmp/.succession-identity-refresh-<session-id>` so it doesn't pollute
the project tree. The `identity-` prefix is historical (Phase 2
coexistence window); renaming would drop in-flight counters.

## Async job queue

PostToolUse (judge) and Stop (LLM reconcile) do their heavy work
asynchronously. Both hooks enqueue a job file under
`.succession/staging/jobs/` and ensure a single `bb succession worker
drain` process is alive to drain it. The worker self-exits after
`:idle-timeout-seconds` of inactivity.

**If async work isn't landing, check:**

- `.succession/staging/jobs/*.json` — pending jobs
- `.succession/staging/jobs/.inflight/*.json` — claimed by a worker
- `.succession/staging/jobs/dead/*.json` — failed jobs; each has a
  sibling `.error.edn` with the exception snapshot. Re-queue by
  moving the `.json` back to `.succession/staging/jobs/`.
- `.succession/staging/jobs/.worker.lock` — at-most-one worker
  guarantee. A lock older than `:stale-lock-seconds` (default 60s) is
  cleared automatically by the next hook invocation.
- `/tmp/.succession-drain-worker.log` — worker stdout/stderr.

**Config (`.succession/config.edn`, under `:worker/async`):**

| Key | Default | Purpose |
|-----|---------|---------|
| `:idle-timeout-seconds` | 10 | Grace window after last activity before worker exits |
| `:parallelism` | 2 | Concurrent handler lanes (caps concurrent LLM calls) |
| `:stale-lock-seconds` | 60 | mtime age past which `.worker.lock` is considered abandoned |
| `:heartbeat-seconds` | 20 | How often the live worker rewrites the lock body |
| `:scan-interval-ms` | 500 | How often the scanner walks `jobs/` |
| `:dead-letter-enabled` | true | Whether to keep failed jobs under `dead/` (else drop) |

There is no automatic retry for handler failures — LLM errors rarely
self-heal. The only retry path is the inflight sweep: a job file left
in `.inflight/` after a kill-9 is moved back to `jobs/` by the next
worker's startup sweep.

## Environment variables

| Variable | Description |
|----------|-------------|
| `SUCCESSION_BB_SRC` | Fallback classpath root used by the hook-layer worker spawn if `<user.dir>/bb/src` does not exist. |
| `CLAUDE_PROJECT_DIR` | Set by Claude Code's harness. `install` bakes `$CLAUDE_PROJECT_DIR/bb/src` (or `/src`) into the settings.json hook commands so one install moves with the project. |

## Exit codes

| Code | Meaning |
|------|---------|
| `0`  | Success |
| `1`  | Command-level failure (LLM error, parse error, no files found) |
| `2`  | Unknown subcommand / mode, or missing required argument |

Hooks do not use exit codes beyond 0 — any non-zero would be reported
to the Claude Code harness as a hook failure. Errors are logged to
stderr and swallowed; the hook always returns nil.
