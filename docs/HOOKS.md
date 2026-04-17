# Succession Hooks

Per-hook deep dive. Every Succession entry point via Claude Code's hook
pipeline is documented here — purpose, stdin/stdout shape, side effects,
latency budget, config knobs, and gotchas.

For the top-down architectural context see
[ARCHITECTURE.md](ARCHITECTURE.md). For command-line usage see
[MANUAL.md](MANUAL.md).

## Hook contract summary

| Event              | Handler ns                             | Lane    | Budget | Emits `additionalContext`? | Writes to disk |
|--------------------|----------------------------------------|---------|--------|---------------------------|----------------|
| `SessionStart`     | `succession.hook.session-start`        | sync    | <1 s   | yes                       | no             |
| `UserPromptSubmit` | `succession.hook.user-prompt-submit`   | sync    | ≤2 s   | no                        | on regex match |
| `PreToolUse`       | `succession.hook.pre-tool-use`         | sync    | <1 s   | when ranked pool non-empty | no             |
| `PostToolUse`      | `succession.hook.post-tool-use`        | sync + async subprocess | <1 s sync / unbounded async | gated | `obs/*` (fingerprint match), judge verdicts (async) |
| `Stop`             | `succession.hook.stop`                 | sync + async subprocess | ≤2 s sync / unbounded async | no | contradictions, deltas, staging snapshot |
| `PreCompact`       | `succession.hook.pre-compact`          | sync    | unbounded (blocks compaction) | no | the whole promoted tree (under lock) |

All six handlers follow the same skeleton in `hook/common.clj`:

```clojure
(defn run []
  (try
    (let [input        (common/read-input)
          project-root (common/project-root input)   ; walk-up from :cwd
          cfg          (common/load-config input)]
      …)
    (catch Throwable t
      (binding [*out* *err*]
        (println "succession <hook> error:" (.getMessage t)))))
  nil)
```

`common/project-root` walks **up** the directory tree from `:cwd` until it
finds a directory containing `.succession/config.edn` or `.git`. This
prevents split queues when Claude Code's session cwd is a subdirectory
— without the walk-up, hooks fired from a subdirectory would create a
second `.succession/` store under that subdirectory that the user never
sees.

No uncaught exception is ever propagated to the Claude Code harness.

## SessionStart

**Purpose.** Auditability and first-turn priming. Render the current
promoted card tree as a markdown behavior tree and surface any orphan
staging from previous crashed sessions. This is NOT the load-bearing
delivery path — Finding 1 established that SessionStart
`additionalContext` is near-inert for behavioral uplift (see
[ARCHITECTURE.md §Finding 1](ARCHITECTURE.md#finding-1-in-one-paragraph)).

**Stdin (from Claude Code):**

```json
{
  "cwd": "/path/to/project",
  "session_id": "session-xyz",
  "hook_event_name": "SessionStart"
}
```

**Stdout:**

```json
{
  "hookSpecificOutput": {
    "hookEventName": "SessionStart",
    "additionalContext": "## Principle · inviolable\n- …\n\n## Rule · default\n…\n\n> **Pending reconciliation:** …"
  }
}
```

**Side effects.** None. Orphan staging is surfaced as a markdown blurb
— never auto-promoted. Promotion happens only under the PreCompact lock.

**Latency budget.** <1 s. No LLM, one `promoted.edn` read + one
observation walk for `score-cards`.

**Config knobs.** `:salience/profile` influences the tree render;
nothing else.

**Gotchas.**

- Do not mis-wire this as the primary refresh channel. Finding 1 showed
  that rules delivered at SessionStart produce 0 productive tool calls
  vs 18 via PostToolUse refresh.
- Orphan staging is detected but not cleared. The next PreCompact folds
  the deltas in.

Reference: `src/succession/hook/session_start.clj`.

## UserPromptSubmit

**Purpose.** Capture user corrections as staged deltas. The hook scans
the incoming prompt with cheap regexes and, on a hit, appends a
`:mark-contradiction` delta so downstream extract/reconcile can bind
the correction to a card.

**Stdin:**

```json
{
  "cwd": "/path/to/project",
  "session_id": "session-xyz",
  "prompt": "no, use Edit instead of Write for that file",
  "hook_event_name": "UserPromptSubmit"
}
```

**Stdout.** None. The prompt itself is already in the agent's input;
injecting a reminder on top of it is noise.

**Side effects.** On a regex match, appends to
`.succession/staging/<session-id>/deltas.jsonl`:

```clojure
{:succession/entity-type :delta
 :delta/id       "d-correction-<uuid>"
 :delta/at       #inst "…"
 :delta/kind     :mark-contradiction
 :delta/payload  {:matched-pattern "(?i)\\bno,?\\s+(?:use|do|try)\\b"
                  :prompt-prefix   "no, use Edit instead of Write for that file"
                  :kind            :user-correction}
 :delta/source   :user-correction}
```

No observation is written — the correction has no bound card id yet,
and `domain/observation` requires one.

**Latency budget.** ≤2 s. Single pass over `:correction/patterns`;
typical run is milliseconds.

**Config knobs.** `:correction/patterns` — a vector of regex strings.
Deliberately broad; the extract LLM filters downstream.

**Gotchas.** If the prompt is blank the hook is a no-op. If a pattern
fails to compile it's silently skipped (caller-defined patterns should
not fail compilation, but we don't want one typo to break the hook).

Reference: `src/succession/hook/user_prompt_submit.clj`.

## PreToolUse

**Purpose.** Pure salient-card lookup adjacent to the upcoming tool
call. Ranks the current card set against the tool descriptor and
emits a ~300-byte reminder.

**Stdin:**

```json
{
  "cwd": "/path/to/project",
  "session_id": "session-xyz",
  "tool_name": "Bash",
  "tool_input": {"command": "git push --force origin main"},
  "hook_event_name": "PreToolUse"
}
```

**Stdout:**

```json
{
  "hookSpecificOutput": {
    "hookEventName": "PreToolUse",
    "additionalContext": "**Salient identity — upcoming tool**\n- …"
  }
}
```

Emitted only when `salience/rank` returns a non-empty list.

**Side effects.** None. No disk writes, no LLM, no `updatedInput`
mutation. Identity is advisory — enforcement belongs elsewhere.

**Latency budget.** <1 s. Shares the salience+render pipeline with
PostToolUse's sync lane, so they stay in lock-step.

**Config knobs.** `:salience/profile` — feature weights, `:top-k`,
`:byte-cap`.

**Gotchas.**

- No refresh gate. PreToolUse fires on every tool call by design — the
  moment before a tool call is exactly when we want salient cards
  visible, regardless of how recently the last reminder fired.
- The tool descriptor format
  (`"tool=<name>,input=<edn>"`) must stay substring-compatible with
  PostToolUse so card `:card/fingerprint` values match both hooks.

Reference: `src/succession/hook/pre_tool_use.clj`.

## PostToolUse

**Purpose.** The Finding 1 hot path. Two lanes:

1. **Sync lane** — deterministic fingerprint observation +
   adjacent-to-now refresh reminder (the load-bearing delivery channel).
2. **Async lane** — enqueues a `:judge` job to the filesystem queue
   and ensures a drain worker is running. The worker claims the job,
   runs `llm/judge/judge-tool-call`, and writes verdict observations.
   The hook returns immediately — the agent turn is never blocked by
   the judge. See [ARCHITECTURE.md §Async job queue](ARCHITECTURE.md#async-job-queue).

**Stdin:**

```json
{
  "cwd": "/path/to/project",
  "session_id": "session-xyz",
  "tool_name": "Edit",
  "tool_input": {"file_path": "/p/src/x.clj", "old_string": "…", "new_string": "…"},
  "tool_response": {"success": true},
  "transcript_path": "/tmp/claude-code-transcript-xyz.jsonl",
  "hook_event_name": "PostToolUse"
}
```

**Stdout.** When the refresh gate opens:

```json
{
  "hookSpecificOutput": {
    "hookEventName": "PostToolUse",
    "additionalContext": "**Identity reminder**\n- verify-via-repl — Use replsh eval instead of mental tracing …"
  }
}
```

Otherwise empty.

**Side effects.**

- `/tmp/.succession-identity-refresh-<session-id>` — refresh gate state
  (calls, emits, last-emit-call, last-emit-bytes). Written every call.
- On fingerprint match: one `:invoked` observation under
  `.succession/observations/<session-id>/`.
- One job file enqueued under `.succession/staging/jobs/<ts>-<uuid>.json`
  containing `{:job/type "judge" :job/payload {:tool-name … :tool-input …}}`.
- A detached `succession worker drain` process may be spawned if
  `.succession/staging/jobs/.worker.lock` is absent or stale. Worker
  stdout/stderr go to `.succession/staging/jobs/.worker.log` (project-scoped;
  also readable via `succession queue status`).

**Refresh gate (byte-delta only).**

```clojure
:refresh/gate
{:byte-threshold        200000   ; emit once ≥200KB of transcript accumulated since last emit
 :cold-start-skip-bytes 50000}   ; skip while the transcript is smaller than 50KB
```

`should-emit?` opens the gate when `cur-bytes ≥ cold-start-skip-bytes`
AND either the emit counter is zero (first emit past cold-start) or
`cur-bytes - last-emit-bytes ≥ byte-threshold`. There is no cap and
no wall-clock component — the infinite-context axiom makes time
meaningless inside a session, so bytes-since-last-emit is the sole
pacing signal. Pre- and PostToolUse share the same state file keyed
by `session_id`, so parallel tool batches deduplicate naturally.

**Latency budget.** <1 s for the sync lane. The async lane is
unbounded — it runs to completion in the background and never blocks
the parent return. If `spawn-judge!` fails the sync lane still emitted
the refresh reminder, so the session is degraded but operational.

**Config knobs.**

| Key | Purpose |
|-----|---------|
| `:refresh/gate` | Byte-delta gate parameters (shared by Pre- and PostToolUse) |
| `:salience/profile` | Ranking + byte-cap for the reminder body |
| `:consult/advisory :every-n-emits` | Periodic "you can consult" reminder, counted in gate emissions |
| `:judge/llm :model` / `:timeout-seconds` | Async lane LLM config |

**Gotchas.**

- The sync lane must return in under ~1 second or Claude Code's next
  API request stalls. Keep the sync path free of LLM calls and heavy
  disk work.
- The hook does not wait on the worker. `enqueue-and-ensure-worker!`
  writes the job file, then (if no live lock) detaches a `succession
  worker drain` process with `:shutdown nil` and returns. A spawn failure
  is swallowed — the sync lane already emitted its reminder, so the
  session is degraded but operational.
- Refresh state file prefix `identity-` is historical (Phase 2
  coexistence window). Active sessions rely on the existing file name;
  renaming would reset in-flight counters.
- `asyncRewake` is deferred. The async judge writes observations, but
  there is no path for its output to re-enter the current turn. That
  headless-continuation-loop integration is future work.
- `transcript/recent-context` returns `nil` when `:transcript_path` is
  absent from the hook payload, points to a missing file, or the file
  cannot be parsed as JSONL. The async lane handles nil gracefully: the
  `:judge` job is enqueued with `{:recent-context nil}` in its payload and
  the judge LLM call proceeds without context text. No error is thrown;
  verdict quality may be slightly lower on sessions where the transcript
  path is unavailable.

Reference: `src/succession/hook/post_tool_use.clj`.

## Stop

**Purpose.** End-of-turn reconcile pass. Pure detectors run inline;
LLM-backed detectors (semantic opposition, principle-tier
observation/card conflicts) are enqueued as a `:llm-reconcile` job and
run on the shared drain worker.

**Stdin:**

```json
{
  "cwd": "/path/to/project",
  "session_id": "session-xyz",
  "hook_event_name": "Stop"
}
```

**Stdout.** None. Per plan, Stop is a background pass — emitting a
reminder at end-of-turn is wasted work since the agent is done.

**Side effects.**

- `.succession/contradictions/<contradiction-id>.edn` — one file per
  detected contradiction (categories 1, 4, 5, 6 — all go to the canonical file; 4 and 5 are pure-only, 1 and 6 also get LLM resolution).
- `.succession/staging/<session-id>/deltas.jsonl` — a
  `:mark-contradiction` delta per detection, so PreCompact can act.
- `.succession/staging/<session-id>/snapshot.edn` — rematerialized so
  consult's hot path reflects what reconcile just saw.
- One job file enqueued under `.succession/staging/jobs/<ts>-<uuid>.json`
  with `{:job/type "llm-reconcile"}`. The drain worker's `:llm-reconcile`
  handler walks `store/contradictions/open-contradictions` and calls
  the appropriate resolver: `resolve-category-2`, `-category-3-principle`,
  `-self-contradictory` (cat-1), or `-contextual-override` (cat-6).
  Resolutions that pass `auto-applicable?` are marked resolved via
  `store/contradictions/mark-resolved!` (5-arity, storing the resolution
  map including `:new-text`). At the next PreCompact, pending rewrites
  are applied to card text via `apply-pending-llm-rewrites`. A detached `succession worker
  drain` process may be spawned if no live lock is held.

**Latency budget.** ≤2 s for the pure pass (bounded by disk reads over
all observations). The async lane runs in the shared drain worker and
is unbounded; the hook never waits on it.

**Config knobs.** `:reconcile/llm` — model, timeout, and
`:auto-apply-confidence` (default 0.8).

**Gotchas.**

- Stop does not emit `systemMessage`. The agent is done for the turn;
  messages here are discarded.
- The async lane is decoupled: Stop enqueues a `:llm-reconcile` job
  and returns. The drain worker is a separate `succession worker
  drain` process with its own entrypoint, so there is no recursive
  hook re-entry concern — no subprocess guard needed.
- The pure pass reads every observation on disk. On very large stores
  this is the hook most likely to slip past the ≤2 s budget; retention
  (`:retention/raw-observations-sessions`, default 50) is the
  countermeasure.
- `run-pure-reconcile!` deduplicates before writing contradiction records:
  if a `(card-id, category)` pair already has an open (unresolved)
  contradiction in `.succession/contradictions/`, the pure pass skips that
  pair. This prevents duplicate contradiction files when Stop fires multiple
  times against the same card state within a session.

Reference: `src/succession/hook/stop.clj`.

## PreCompact

**Purpose.** The only real promotion site. Everything else is
append-only live state; only PreCompact acquires the promote lock and
rewrites `identity/promoted/`.

**Stdin:**

```json
{
  "cwd": "/path/to/project",
  "session_id": "session-xyz",
  "hook_event_name": "PreCompact"
}
```

**Stdout.** None. PreCompact runs at compaction time, when the model
is not reading hook output anyway.

**Side effects (under the promote lock).**

1. `archive/{ts}/promoted/` — pre-promotion snapshot (via
   `store/archive/snapshot!`). Taken before any rewrite, so an
   interrupted promotion is recoverable.
2. Delete every `.md` under `identity/promoted/{tier}/`.
3. Rewrite the tree from the working map of cards.
4. Regenerate `promoted.edn` (fast-read materialized snapshot).
5. Clear `staging/<session-id>/`.

Delta application folds are pure (`apply-delta`), one kind per case:

| Delta kind             | Effect |
|------------------------|--------|
| `:create-card`         | New card at `:ethic` (or payload tier); provenance from session |
| `:update-card-text`    | `card/rewrite` — new text, appends `:rewrites` backlink |
| `:propose-tier`        | `card/retier` — directly sets tier if in valid set |
| `:propose-merge`       | Rewrite survivor with merged text, drop loser card |
| `:observe-card`        | No-op (observations live in their own files) |
| `:mark-contradiction`  | No-op (contradictions written at detection time) |

After delta application, `retier-by-metrics` recomputes each card's
eligible tier via `tier/propose-transition` and applies any
`:promote`/`:demote` that fires. Hysteresis is enforced inside
`eligible-tier`, so cards near thresholds don't flicker.
If the card carries `:card/tier-bounds`, the computed tier is clamped
to the declared floor/max before writing.

**Latency budget.** Unbounded — PreCompact runs synchronously and
blocks compaction. In practice dominated by disk I/O over the
observation store.

**Config knobs.** `:tier/rules` (hysteresis bands), `:weight/*` (what
gets computed for each card).

**Gotchas.**

- The archive snapshot happens *before* `clear-tier-files!`. A crash
  after snapshot but before rewrite leaves the live tree empty but the
  archive intact — recoverable by copying the archive back.
- `:observe-card` and `:mark-contradiction` deltas are no-ops here.
  Their payloads are consumed by other code paths (observations
  directly on disk, contradictions via `store/contradictions`), so
  applying them again at PreCompact would be double counting.
- A failing PreCompact must not block compaction. The whole body is in
  a try/catch; any throwable is caught, logged to stderr, and
  swallowed. The lock releases on scope exit regardless.

Reference: `src/succession/hook/pre_compact.clj`.

## Async drain worker model

PostToolUse and Stop share a single filesystem-backed job queue drained
by one `succession worker drain` process. The hooks never spawn a
per-call child — they enqueue a job file and ensure a worker is alive.

### Disk layout

```
.succession/staging/jobs/
├── <ts>-<uuid>.json         ; pending
├── .inflight/
│   └── <ts>-<uuid>.json     ; claimed by a worker
├── dead/
│   ├── <ts>-<uuid>.json     ; handler threw
│   └── <ts>-<uuid>.error.edn
├── .worker.log              ; drain worker event log (tail -f friendly)
└── .worker.lock             ; at-most-one worker
```

Filenames use ISO-8601 basic timestamp prefixes
(`yyyyMMdd'T'HHmmssSSS'Z'`), so lexicographic sort equals chronological
order — that is the FIFO mechanism. Enqueue writes `<name>.json.tmp`
then atomic-renames to `<name>.json`; `list-pending` ignores anything
not ending in `.json`.

### Hook side (enqueue)

```clojure
(common/enqueue-and-ensure-worker!
  project-root cfg
  {:type    :judge               ; or :llm-reconcile
   :session session
   :payload {...}})
```

`enqueue-and-ensure-worker!` writes the job file, then checks
`.worker.lock`. If the lock is absent or its mtime is older than
`:stale-lock-seconds` (default 60), it detaches:

```clojure
(process/process
  {:dir      project-root
   :out      (paths/jobs-worker-log project-root)   ; .succession/staging/jobs/.worker.log
   :err      (paths/jobs-worker-log project-root)
   :shutdown nil}
  "bb" "-cp" (src-root) "-m" "succession.core" "worker" "drain")
```

A fresh lock means a worker is already draining; the hook returns
without spawning. Spawn failures are swallowed — the job file is on
disk, so the next hook invocation will retry the spawn.

### Worker side (drain)

`succession.worker.drain/run!` is the only namespace that imports
`clojure.core.async`. The body:

1. `locks/try-lock-at` on `.worker.lock`. If a fresh lock exists and
   belongs to another worker, exit 0. If the lock is stale,
   break-and-retake.
2. `jobs/sweep-stale-inflight!` — move any `.inflight/<name>.json`
   file older than `:stale-lock-seconds` back to `jobs/` for retry.
   This is the *only* retry path in the system; handler failures do
   not retry.
3. Spin up three `a/thread` loops plus a `pipeline-blocking` with
   `:parallelism` lanes (default 2):
   - **Scanner** — every `:scan-interval-ms`, list pending, sort lex,
     `claim!` each (atomic rename into `.inflight/`), `>!!` onto
     `jobs-chan`.
   - **Pipeline** — `handle-job!` multimethod on `:job/type`:
     `:judge` → `llm/judge/judge-tool-call`; `:llm-reconcile` →
     `llm/reconcile/resolve-*`. Each call is wrapped into a pure
     `Result` value (`:ok` or `:error`).
   - **Heartbeat** — every `:heartbeat-seconds`, rewrite
     `.worker.lock` body and touch mtime.
   - **Idle watcher** — when `domain/queue/idle?` holds (pending and
     inflight both zero, grace window elapsed), close `jobs-chan`.
     The idle watcher does NOT sweep inflight files; that sweep runs
     only at step 2 (startup), targeting files from a previous crashed
     worker. Running the sweep on every tick caused an infinite claim
     loop when `ATOMIC_MOVE` preserved the enqueue mtime on the
     inflight file — the sweep would reclaim a still-running job.
4. Main thread drains `results-chan`. For each result: on `:ok`
   `jobs/complete!` (delete inflight), on `:error`
   `jobs/dead-letter!` (move inflight to `dead/<name>.json` and write
   sibling `<name>.error.edn`).
5. On close, release the lock and `System/exit 0`.

### Invariants and failure modes

- **At-most-one worker.** `Files/createFile` on `.worker.lock` is
  atomic; losers exit 0 immediately.
- **Circuit-broken retries.** Each claim increments `:job/attempts` in
  the inflight file. If `attempts ≥ max-attempts` (default 10) OR
  claims in the last hour `≥ max-attempts-per-hour` (default 5), the
  scanner dead-letters the job immediately instead of handing it to
  the pipeline. Handler failures (non-loop) land in `dead/` with a
  sibling `.error.edn`; operators re-queue via `succession queue
  requeue <filename>`.
- **Crash recovery.** A kill-9 mid-job leaves `.inflight/<name>.json`
  behind. The next worker's startup sweep moves it back to `jobs/`
  after `:stale-lock-seconds`.
- **FIFO preservation on retry.** The swept-back file keeps its
  original timestamp prefix, so retries don't jump the line.
- **No recursive hook re-entry.** The worker is launched via
  `succession.core worker drain`, which is a distinct entrypoint from
  `succession.core hook …`. A tool call the worker may make (via its
  own LLM shell-out) does not re-enter any hook. The old
  `SUCCESSION_JUDGE_SUBPROCESS` env guard is gone.

### Config — `:worker/async`

| Key | Default | Purpose |
|-----|---------|---------|
| `:idle-timeout-seconds` | 30 | Grace window after last activity before scanner closes the input chan |
| `:parallelism` | 2 | Number of `pipeline-blocking` lanes (caps concurrent LLM calls) |
| `:stale-lock-seconds` | 60 | mtime age past which the lock is considered abandoned |
| `:heartbeat-seconds` | 20 | How often the worker rewrites the lock body |
| `:scan-interval-ms` | 500 | How often the scanner walks `jobs/` |
| `:dead-letter-enabled` | true | Whether to keep failed jobs under `dead/` (else drop) |
| `:inflight-sweep-seconds` | 600 | How long a job may sit in `.inflight/` before startup sweep reclaims it (must exceed max LLM call duration) |
| `:max-attempts` | 10 | Lifetime claim limit per job; exceeded → dead-letter (circuit breaker) |
| `:max-attempts-per-hour` | 5 | Rolling 60-min claim limit; exceeded → dead-letter (circuit breaker) |

**`asyncRewake` is still deferred.** Verdicts produced by the drain
worker after the parent returned cannot re-enter the current turn.
They land as observations and affect the *next* turn's salience
ranking. Plumbing them into the current turn would require Claude
Code's headless continuation loop, which is still under
investigation.
