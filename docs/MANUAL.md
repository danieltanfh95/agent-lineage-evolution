# Succession Manual

Exhaustive command and hook reference. For the architectural context see
[ARCHITECTURE.md](ARCHITECTURE.md); for per-hook stdin/stdout shapes see
[HOOKS.md](HOOKS.md).

## Synopsis

```
succession <command> [args…]
```

Two kinds of invocation:

- `succession hook <event>` — wired into Claude Code via
  `.claude/settings.local.json`. Reads one JSON object on stdin, emits zero
  or one on stdout.
- `succession <cli-subcommand>` — invoked by the user or the
  agent via Bash. Prints to stdout, exits with a meaningful status code.

Every command loads its effective config via
`succession.config/load-config <project-root>`, which deep-merges
`.succession/config.edn` over `default-config`. See [Config file
reference](#config-file-reference).

`project-root` for hooks is derived from the `:cwd` field in the hook
payload by walking up the directory tree until a directory containing
`.succession/config.edn` or `.git` is found. This prevents a split-queue
bug when Claude Code's session cwd is a subdirectory.

## Commands

### consult

Reflective second-opinion query against the current identity. The agent
invokes it via Bash when uncertain; humans run it to see what the agent
would see.

```
succession consult "<situation>" [flags…]
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
succession consult "about to force-push to main"
succession consult "rewriting a test file" --tool-name Edit --intent "fix assertion"
succession consult "ship the migration" --tier principle --dry-run
```

Output: the LLM reflection (four tier sections + tensions + reflection).
Every card that made it into the candidate pool is logged as a
`:consulted` observation — weight-neutral but visible in the audit
trail. Exit 0 on success, 1 on LLM error or missing situation.

Reference: `src/succession/cli/consult.clj`.

### replay

Walk a Claude Code transcript JSONL through the identity cycle in a
sandbox directory. Used to catch shape regressions across the
store/domain/LLM boundary without actually calling the judge.

```
succession replay <transcript.jsonl>
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

Reference: `src/succession/cli/replay.clj`.

### config

Three subcommands for the `.succession/config.edn` file.

```
succession config <validate | show | init>
```

- **`validate`** — read the effective config (defaults + overlay) and
  run `config/validate`. Exits 0 if clean, 1 if any `{:path :problem}`
  is reported. Prints each problem.
- **`show`** — pretty-print the effective config to stdout. Exit 0.
- **`init`** — write a starter `.succession/config.edn` populated with
  the defaults and inline comments. Refuses to overwrite an existing
  file (exit 1). Idempotent for fresh installs.

Reference: `src/succession/cli/config_validate.clj`.

### install

One-shot atomic setup of everything Succession needs in a project.

```
succession install
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
  each pointing at the global `succession` binary. Existing
  non-Succession hooks are preserved.

Prints a per-step report (`ok` / `skip` / `ERR`) and exits 0 if every
step is `:ok` or `:skipped`.

Reference: `src/succession/cli/install.clj`.

### identity-diff

Compare two archived identity snapshots. Every PreCompact writes an
archive under `.succession/archive/{ts}/promoted/…` before rewriting
the live tree; `identity-diff` shows what changed.

```
succession identity-diff [list|last|<ts1> [ts2]]
```

- **`list`** — Tabulate all archive snapshots (timestamp + card count), newest first.
- **`last`** (default) — Diff the two most recent snapshots.
- **`<ts1> [ts2]`** — Diff specific snapshots; `ts2` defaults to `"current"` (live promoted tree).

Reports four change categories:

- `added`     — cards present in `ts2`, absent in `ts1`
- `removed`   — cards present in `ts1`, absent in `ts2`
- `retiered`  — same id, different `:card/tier`
- `rewritten` — same id, same tier, different `:card/text`

Exit 0 on successful diff (including "no differences"), 2 on missing
args.

Reference: `src/succession/cli/identity_diff.clj`.

### show

Pretty-print the live promoted identity — the same rendering
SessionStart serves as `additionalContext`, on demand.

```
succession show [--format markdown|edn]
```

- `--format markdown` (default) — tiered tree under `## Principles /
  Rules / Ethics`, same renderer as SessionStart.
- `--format edn` — one `pr-str` map per card, one per line. Round-
  trips via `read-string`, suitable for piping into other tooling.

Exit 0 on success. Empty store prints `_No promoted identity cards
yet._` and still returns 0.

Reference: `src/succession/cli/show.clj`.

### queue

Inspect and recover the async job queue (see the Async job queue
section below for context on what the queue is and when it goes
wrong).

```
succession queue <status | list-dead | requeue | clear-dead>
```

Subcommands:

- **`status`** — one-line counters: `N pending · N inflight · N dead
  · <lock-state>`. If `dead > 0`, a hint points at `list-dead`.
- **`list-dead`** — tabular view of dead-lettered jobs with filename,
  type, session, and the first line of the exception message. The
  full stack trace lives in the sibling `.error.edn` under `:trace`.
- **`requeue <filename>`** — move one dead job back to `jobs/`.
  Filename is preserved so the job keeps its original FIFO position.
- **`requeue --all`** — same, for every dead job.
- **`clear-dead`** — delete every dead pair.
- **`clear-dead --older-than <N(s|m|h|d)>`** — only delete pairs
  whose on-disk mtime is older than the cutoff (e.g. `7d`, `12h`).

Exit 0 on success, 1 on invalid usage or unknown subcommand.

Reference: `src/succession/cli/queue.clj`.

### bench

Run the judge regression/cost/latency harness. Tests a set of LLM models
against 9 fixture tool-call cases and emits a comparison table.

```
succession bench [flags…]
```

| Flag | Default | Description |
|------|---------|-------------|
| `--models m1,m2,…` | curated 6 | Comma-separated model IDs (see BENCH.md for the full list) |
| `--models all` | — | Full sweep (13 models) |
| `--runs N` | `1` | Runs per case per model (use ≥5 for stable averages; single-run scores are noisy) |
| `--timeout N` | `45` | Per-call LLM timeout in seconds |
| `--output-dir PATH` | `.succession/bench/` | EDN output directory |

Output to stdout: one progress dot per case (`.` = correct, `~` = wrong,
`x` = parse failure), then a markdown table with Parse%, Accuracy%, Avg
Cost, Avg Latency, Avg Output Tokens, and a letter grade. Full per-case
detail is written to `<output-dir>/<timestamp>.edn` for later analysis.

When to use: before switching the judge model in `.succession/config.edn`.
The bench exercises the exact judge code paths (`judge/build-tool-prompt` +
`judge/parse-response`) so the grade directly predicts production accuracy.

See [BENCH.md](BENCH.md) for fixture case descriptions, scoring rubric, and
historical results.

Exit 0 on success, 1 on transport error or missing models.

Reference: `src/succession/cli/bench.clj`.

### import

One-shot migration of an old `.succession/rules/*.md` YAML-frontmatter
directory into the new card store.

```
succession import <old-rules-dir>
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

Reference: `src/succession/cli/import.clj`.

### compact

Manually trigger the PreCompact promotion pipeline outside the normal
Claude Code compaction flow.

```
succession compact
```

Reads all `staging/{session-id}/deltas.jsonl` files not yet promoted,
applies them to the working card set (`:create-card`, `:update-card-text`,
`:propose-tier`, `:propose-merge`), retiers every card by current metrics,
archives the pre-promotion state to `.succession/archive/{ts}/`, rewrites
`identity/promoted/` atomically under the promote lock, and clears the
processed staging directories.

**When to use vs PreCompact hook:**

- **PreCompact hook** runs automatically when Claude Code compacts the
  context window. This is the normal promotion path.
- **`compact`** is for out-of-band promotion: after a long session where
  async reconcile has settled but the context has not yet been compacted;
  before starting a new session to verify the current identity state; or
  after manually editing card files and wanting the promoted snapshot
  refreshed.

A failing `compact` does not corrupt the promoted tree — the archive
snapshot is written before any card file is touched, so an interrupted
promotion is recoverable by copying the archive back.

Exit 0 on success, 1 on lock acquisition failure or card-write error.

Reference: `src/succession/hook/pre_compact.clj` (the same `promote!`
function the hook calls; the CLI is a thin wrapper).

### status

Dashboard overview of the `.succession/` folder — card counts by tier, observation
counts, archive count, contradiction counts, queue state, and staging session count.

```
succession status
```

Prints a single-screen summary of all succession state:

- **Cards by tier** — count of promoted cards at each tier (principle / rule / ethic)
- **Observations** — total raw observations on disk
- **Archive** — number of archive snapshots
- **Contradictions** — open vs resolved counts
- **Queue** — pending / inflight / dead job counts and `.worker.lock` state
- **Staging sessions** — count of staging session directories (including orphaned)

Exit 0 on success.

Reference: `src/succession/cli/status.clj`.

### staging

Inspect and prune intra-session staging directories under
`.succession/staging/`.

```
succession staging <status | prune [flags…]>
```

Subcommands:

- **`status`** — print a summary of all staging directories: session id,
  delta count, snapshot presence, and last-modified age. Sessions with
  orphaned staging (previous sessions whose deltas have not yet been
  promoted) are flagged.
- **`prune [flags]`** — delete staging directories matching the filter.

  | Flag | Description |
  |------|-------------|
  | `--keep-last N` | Keep the N most recent sessions' staging; delete the rest |
  | `--older-than Nd` | Delete directories last modified more than N days ago (also accepts `Nh` for hours) |

  Without a flag, `prune` prints what would be deleted (dry run) without
  deleting anything.

`prune` never deletes the current active session's directory. Observations
under `.succession/observations/` are not touched — only staging delta logs
(`deltas.jsonl`) and materialized snapshots (`snapshot.edn`).

Exit 0 on success, 1 on invalid usage.

### hook &lt;event&gt;

Invoked by Claude Code via `.claude/settings.local.json`. Reads one JSON
object on stdin, runs the event handler, emits zero or one JSON object
on stdout, never throws.

```
succession hook <event>
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
| `:reconcile/llm :model` | `deepseek/deepseek-chat` | 90 | `:auto-apply-confidence` 0.8; `:max-batch-size` 10 (all open contradictions resolved in one LLM call) |
| `:judge/llm :model` | `deepseek/deepseek-chat` | 30 | async PostToolUse verdict lane |
| `:consult/llm :model` | `deepseek/deepseek-chat` | 60 | `bb consult` reflection |

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
`src/succession/store/paths.clj`.

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
│       ├── .worker.log                        drain worker event log (tail -f friendly)
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
`.succession/staging/jobs/` and ensure a single `succession worker
drain` process is alive to drain it. The worker self-exits after
`:idle-timeout-seconds` of inactivity.

**If async work isn't landing, check:**

- `succession queue status` — one-line counters (pending / inflight
  / dead) plus `.worker.lock` state. This is the fastest way to tell
  whether jobs are piling up or draining cleanly.
- `succession queue list-dead` — tabular view of dead-lettered jobs
  with the first line of each exception message. Sibling `.error.edn`
  files carry the full stack trace under `:trace`.
- `succession queue requeue <filename>` or `requeue --all` — move
  dead jobs back to `jobs/` for another attempt (filename preserved,
  so FIFO order is retained). Use this after fixing a bug that dead-
  lettered a batch.
- `succession queue clear-dead [--older-than 7d]` — delete dead
  pairs you've decided you don't want to replay.
- `.succession/staging/jobs/.worker.log` — structured worker event log (one
  line per event). `tail -f` this file during a live drain to see
  `scanner/tick`, `scanner/claimed`, `job/complete`, and `idle/fire` events
  in real time. The last 20 lines also appear in `succession queue status`.

A lock older than `:stale-lock-seconds` (default 60s) is cleared
automatically by the next hook invocation.

**Config (`.succession/config.edn`, under `:worker/async`):**

| Key | Default | Purpose |
|-----|---------|---------|
| `:idle-timeout-seconds` | 30 | Grace window after last activity before worker exits |
| `:parallelism` | 2 | Concurrent handler lanes (caps concurrent LLM calls) |
| `:stale-lock-seconds` | 60 | mtime age past which `.worker.lock` is considered abandoned |
| `:heartbeat-seconds` | 20 | How often the live worker rewrites the lock body |
| `:scan-interval-ms` | 500 | How often the scanner walks `jobs/` |
| `:dead-letter-enabled` | true | Whether to keep failed jobs under `dead/` (else drop) |
| `:inflight-sweep-seconds` | 600 | How long a job may sit in `.inflight/` before the startup sweep reclaims it (must exceed max LLM call duration) |
| `:max-attempts` | 10 | Lifetime claim limit per job; exceeded → dead-letter (circuit breaker) |
| `:max-attempts-per-hour` | 5 | Rolling 60-min claim limit; exceeded → dead-letter (circuit breaker) |

There is no automatic retry for handler failures — LLM errors rarely
self-heal. The only retry path is the inflight sweep: a job file left
in `.inflight/` after a kill-9 is moved back to `jobs/` by the next
worker's startup sweep.

## Environment variables

| Variable | Description |
|----------|-------------|
| `CLAUDE_PROJECT_DIR` | Set by Claude Code's harness. Hook commands use the global `succession` binary, so no classpath baking is needed. |

## Exit codes

| Code | Meaning |
|------|---------|
| `0`  | Success |
| `1`  | Command-level failure (LLM error, parse error, no files found) |
| `2`  | Unknown subcommand / mode, or missing required argument |

Hooks do not use exit codes beyond 0 — any non-zero would be reported
to the Claude Code harness as a hook failure. Errors are logged to
stderr and swallowed; the hook always returns nil.
