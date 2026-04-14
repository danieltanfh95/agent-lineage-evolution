# Succession Architecture

How the identity-cycle system is laid out, what each layer is responsible for,
and which invariants hold the pipeline together. For command-line usage see
[MANUAL.md](MANUAL.md); for per-hook contracts see [HOOKS.md](HOOKS.md).

## Layers

```
┌─────────────────────────────────────────────────────┐
│ succession.core         Entry dispatcher            │
├─────────────────────────────────────────────────────┤
│ succession.hook.*       Claude Code lifecycle       │
│ succession.cli.*        User + agent commands       │
│ succession.worker.*     Async drain worker          │
├─────────────────────────────────────────────────────┤
│ succession.llm.*        Judge, extract, reconcile   │
│ succession.store.*      Cards, observations, locks, │
│                         jobs (async queue)          │
├─────────────────────────────────────────────────────┤
│ succession.domain.*     Pure — weight, tier,        │
│                         reconcile, consult, render, │
│                         queue policy                │
└─────────────────────────────────────────────────────┘
                    dependencies point downward only
```

- **`core`** (`src/succession/core.clj`) is thin routing. `succession hook <event>`
  lazily requires the matching `hook.*` ns;
  `succession <cli-cmd>` dispatches to `cli.*`. No domain logic
  lives here.
- **`hook/*`** handle Claude Code's six lifecycle events. Each reads one JSON
  object from stdin, does its work, emits zero or one JSON object on stdout.
  Never throws — errors go to `*err*` and the hook returns nil.
- **`cli/*`** are invoked manually by the user or by the agent via `Bash`.
  `consult`, `replay`, `config`, `install`, `identity-diff`, `import`,
  `compact`, `staging`, `bench`.
- **`llm/*`** wraps `claude -p` subprocess calls (judge, extract, reconcile,
  consult).
- **`store/*`** is the only layer allowed to touch disk. Cards, observations,
  staging, contradictions, locks, archive snapshots, the async job queue.
- **`worker/*`** is the drain worker — the only user of `clojure.core.async`
  in the project. Reads jobs from `store/jobs`, dispatches to `llm/*`
  handlers, writes observations/contradictions back through `store/*`.
  Lives in its own layer because no hook and no CLI ever sees a channel.
- **`domain/*`** is pure. No I/O, no clock (caller passes `now`). Weight
  formula, tier hysteresis, reconcile detectors, consult scoring, rollup,
  render, and the queue policy fns (`sort-jobs`, `idle?`, `job->result`).

The hexagonal invariant is enforced by the layer tests in `test/succession/`:
`domain/*` must not import from `store/*`, `llm/*`, or `hook/*`.

## Data shapes

All entity maps carry `:succession/entity-type` and use namespaced keys. Types
live in their pure namespace alongside the predicate and constructor.

### card (`domain/card.clj`)

```clojure
{:succession/entity-type :card
 :card/id         "prefer-edit-over-write"
 :card/tier       :principle            ; :principle | :rule | :ethic
 :card/category   :strategy             ; see config/valid-categories
 :card/text       "Prefer Edit over Write when modifying existing files…"
 :card/tags       [:file-editing :tooling]
 :card/fingerprint "tool=Edit"          ; optional — pure invocation detection
 :card/provenance {:provenance/born-at         #inst "…"
                   :provenance/born-in-session "abc123"
                   :provenance/born-from       :user-correction
                   :provenance/born-context    "…"}
 :card/rewrites   ["h-deadbeef" …]}     ; optional backlinks
```

Weight and tier-eligibility are NOT stored on the card. They are computed
from the card's observation log on the fly. A card is inert data; everything
interesting is derived.

### observation (`domain/observation.clj`)

```clojure
{:succession/entity-type :observation
 :observation/id         "obs-abc123"
 :observation/at         #inst "2026-04-11T…"
 :observation/session    "session-xyz"
 :observation/hook       :post-tool-use
 :observation/source     :judge-verdict
 :observation/card-id    "prefer-edit-over-write"
 :observation/kind       :confirmed        ; :confirmed | :violated | :invoked
                                           ; :consulted | :contradicted
 :observation/context    "judge: agent used Edit as expected"
 :observation/judge-verdict-id "v-123"     ; optional backref
 :observation/judge-model      "claude-sonnet-4-6"}
```

Observations are append-only. If an observation is wrong, the pipeline writes
a contradiction record rather than editing or deleting it. `:consulted` is
weight-neutral so the agent cannot inflate weight by consulting more.

### delta (`store/staging.clj`)

Intra-session proposals — written by UserPromptSubmit, Stop-time reconcile,
and the extract pipeline. PreCompact folds them into cards.

```clojure
{:succession/entity-type :delta
 :delta/id       "d-correction-…"
 :delta/at       #inst "…"
 :delta/session  "session-xyz"
 :delta/kind     :create-card            ; :create-card | :update-card-text
                                         ; :propose-tier | :propose-merge
                                         ; :observe-card | :mark-contradiction
 :delta/card-id  "prefer-edit-over-write" ; nil for :create-card/:propose-merge
 :delta/payload  { … kind-specific … }
 :delta/source   :extract}               ; :user-correction | :judge | :reconcile
```

### rollup (`domain/rollup.clj`)

Per-session bucket derived from an observation seq — the input shape the
weight formula actually consumes.

```clojure
{"session-xyz"
 {:session/first-at    #inst "…"
  :session/last-at     #inst "…"
  :session/confirmed   3
  :session/violated    0
  :session/invoked     1
  :session/consulted   0
  :session/contradicted 0}}
```

## Data flow per hook

Detailed stdin/stdout contracts live in [HOOKS.md](HOOKS.md). These traces
show which namespaces each hook touches.

### SessionStart

```
stdin JSON
  │
  ▼
hook.session-start/run
  │
  ├─ common/read-input, common/project-root
  ├─ store.cards/read-promoted-snapshot          ← promoted.edn
  ├─ common/score-cards                          ← observations + weight.compute
  ├─ store.sessions/orphan-staging               ← staging/{other-sessions}/
  ├─ domain.render/identity-tree                 ← markdown behavior tree
  │
  ▼
{:hookSpecificOutput {:hookEventName "SessionStart"
                      :additionalContext "…"}}
```

No disk writes. Orphan deltas are surfaced as a pending-reconciliation note,
not auto-promoted — promotion only happens under the PreCompact lock.

### UserPromptSubmit

```
hook.user-prompt-submit/run
  ├─ detect-correction           ← regex sweep over :correction/patterns
  └─ on match:
     store.staging/append-delta! ← :mark-contradiction, source :user-correction
```

No `additionalContext` — the prompt itself is already the agent's input;
injecting a reminder on top of it is noise.

### PreToolUse

```
hook.pre-tool-use/run
  ├─ store.cards/read-promoted-snapshot
  ├─ common/score-cards
  ├─ tool-descriptor = "tool=<name>,input=<edn>"
  ├─ domain.salience/rank  ← feature-weighted, top-k capped, byte-cap enforced
  └─ domain.render/salient-reminder → additionalContext
```

Pure lookup. No disk writes, no LLM, no `updatedInput` mutation — identity
is advisory, not mechanical.

### PostToolUse

Two lanes. The sync lane is the Finding 1 hot path.

```
hook.post-tool-use/run
  │
  ├── sync lane (budget <1s)
  │   ├─ read-state / write-state!        ← /tmp/.succession-identity-refresh-<sess>
  │   ├─ should-emit?                     ← refresh gate (see below)
  │   ├─ fingerprint-invocation           ← substring match on :card/fingerprint
  │   ├─ store.observations/write-observation! (on match, :invoked)
  │   ├─ salience/rank + render/salient-reminder
  │   └─ emit additionalContext if gate opens
  │
  ├── transcript context (feeds async lane)
  │   └─ transcript/recent-context          ← last N user/assistant messages
  │      reads tail of transcript JSONL, filters type ∈ {"user" "assistant"},
  │      truncates per-message, returns formatted string or nil
  │
  └── async lane (filesystem queue + drain worker)
      hook.common/enqueue-and-ensure-worker!
        ├─ store.jobs/enqueue!              ← staging/jobs/<ts>-<uuid>.json
        │    payload includes :recent-context (string or nil)
        └─ ensure-worker-running!           ← spawn drain worker if lock absent
```

**`transcript.clj` utility.** `succession.transcript/recent-context` reads the
tail of the Claude Code session JSONL (path supplied by the hook payload's
`:transcript_path` field), filters to `{"user", "assistant"}` message types,
truncates each message body, and returns a formatted string or `nil`. It is
called in the PostToolUse sync lane before enqueue so the judge job payload
includes conversational context. A `nil` return (missing path, malformed JSONL,
or any I/O error) is handled gracefully: the job is still enqueued with
`{:recent-context nil}` and the judge proceeds without context text.

Source: `src/succession/transcript.clj`.

The sync lane must return before Claude Code's next API request or the agent
turn stalls. The async lane is fire-and-forget from the hook's point of view
— it appends a `:judge` job to `staging/jobs/` and returns. See §Async job
queue below for how the drain worker picks that job up and runs the judge
LLM against it.

### Stop

```
hook.stop/run
  │
  ├── pure reconcile pass
  │   ├─ store.cards/load-all-cards
  │   ├─ store.observations/load-all-observations → observations-by-card
  │   ├─ metrics-by-card                         ← common/metrics-for per card
  │   ├─ domain.reconcile/detect-all             ← categories 1, 4, 5, 6
  │   ├─ store.contradictions/write-contradiction!
  │   ├─ store.staging/append-delta!             ← :mark-contradiction
  │   └─ store.staging/rematerialize!
  │
  └── async LLM reconcile lane (filesystem queue + drain worker)
      hook.common/enqueue-and-ensure-worker!
        ├─ store.jobs/enqueue! :llm-reconcile    ← staging/jobs/<ts>-<uuid>.json
        └─ ensure-worker-running!                ← spawn drain worker if needed
```

Stop emits no `systemMessage` and no `additionalContext`. The agent is done
for the turn.

### PreCompact

The only real promotion site. Everything else is append-only live state.

```
hook.pre-compact/run → promote!
  │
  ├─ store.locks/with-lock        ← acquire .succession/promote.lock
  ├─ store.archive/snapshot!      ← archive/{ts}/promoted snapshot
  ├─ load cards + staging deltas
  ├─ reduce apply-delta           ← :create-card, :update-card-text,
  │                                 :propose-tier, :propose-merge
  ├─ retier-by-metrics            ← domain.tier/propose-transition per card
  ├─ clear-tier-files!            ← delete every promoted/{tier}/*.md
  ├─ write-all-cards!             ← rewrite tree from working map
  ├─ store.cards/materialize-promoted!  ← regenerate promoted.edn snapshot
  ├─ store.staging/clear-session!
  └─ release lock
```

A failing PreCompact must not block compaction — any throwable is caught,
logged, and swallowed. The archive snapshot taken before any rewrite means
an interrupted promotion can always be rolled back from disk.

## Weight formula

The 資治通鑑 principle: a claim observed long ago and re-confirmed now is
more load-bearing than a dense burst of recent observations. Span dominates
frequency. Gap-crossings multiply. Stale claims decay.

```
freq           = count of sessions with weight-contributing observations
span_days      = days between earliest first-at and latest last-at
gap_crossings  = # session boundaries where first-at(N+1) > last-at(N)
violation_rate = rollup/violation-rate (excludes :consulted)
decay          = 0.5 ^ (days_since_last_reinforce / half_life)

freq_term = min(sqrt(freq), freq_cap)
span_term = (1 + log(1 + span_days)) ^ span_exponent
gap_term  = 1 + gap_crossings
within_penalty = (gap_crossings == 0 ? within_session_penalty : 1)

base    = freq_term * span_term * gap_term * within_penalty * decay
penalty = base * violation_rate * violation_penalty_rate
weight  = max(0, base - penalty)
```

Every knob comes from config; nothing is hard-coded. Default values in
`config/default-config`:

| Knob | Default | Effect |
|------|---------|--------|
| `:weight/freq-cap` | 4.0 | ceiling on sqrt(freq) so bursts can't dominate |
| `:weight/span-exponent` | 1.5 | log-span raised to this power |
| `:weight/within-session-penalty` | 0.5 | multiplier when all obs in one session |
| `:weight/decay-half-life-days` | 180 | exponential decay half-life |
| `:weight/violation-penalty-rate` | 0.5 | fraction of `base * violation_rate` to subtract |

### Test battery

Five scenarios pinned in `test/succession/domain/weight_test.clj`.
Assertions are about ordering, not absolute numbers, so the formula can be
tuned without rewriting the tests.

| # | Card A | Card B | Expected | Verifies |
|---|--------|--------|----------|----------|
| 1 | 1000 obs in 1 week, 0 gaps | 2 obs spanning 1 year, 1 gap | **B > A** | span dominates frequency |
| 2 | 5 obs in one session | 3 obs across 3 sessions (30d) | **D > C** | session-density penalty |
| 3 | 4 obs in 2025, silent since | 3 obs in late 2026 | **F > E** | decay penalizes stale cards |
| 4 | 4 sessions, 1 confirmed + 1 violated each | same 4 sessions, confirmed only | **H > G**, and `wg ≈ 0.75 * wh` | violation penalty proportional to config rate |
| 5 | one fresh observation | — | `0 < w < 5.0` | single obs lands in ethic, not principle |

Plus two guards: empty rollup → 0; a card observed only via `:consulted` → 0.

## Tier hysteresis

Three tiers, in load-bearing order: `:ethic → :rule → :principle`. A card's
declared `:card/tier` may disagree with the tier its metrics qualify for;
`domain/tier` computes the eligible tier and proposes a transition.

Hysteresis: cards *enter* a tier when the `:enter` thresholds (all AND) are
met, and *exit* only when `:exit` thresholds (any OR) are triggered. The gap
is the hysteresis band — a card near a threshold will not flicker.

Default bands from `config/default-config`:

```clojure
{:principle {:enter {:min-weight 30.0 :max-violation-rate 0.0 :min-gap-crossings 5}
             :exit  {:max-weight 20.0 :min-violation-rate 0.1}}
 :rule      {:enter {:min-weight 5.0  :max-violation-rate 0.3 :min-gap-crossings 1}
             :exit  {:max-weight 3.0  :min-violation-rate 0.5}}
 :ethic     {:enter {}                          ; default landing tier
             :exit  {:archive-below-weight 0.5}}}
```

`eligible-tier` runs demotion before promotion in the same tick — a card
cannot simultaneously promote and demote. An `:ethic` card whose weight
drops below `:archive-below-weight` becomes `:archived` and drops out of
the promoted tree.

Reference: `src/succession/domain/tier.clj`.

## Reconcile pipeline

Six contradiction categories. Four are pure and handled by `domain/reconcile`
at Stop; two require LLM judgement and run asynchronously.

| # | Category | Detector | Lane | Resolution kind |
|---|----------|----------|------|-----------------|
| 1 | Self-contradictory claim | `detect-self-contradictory` + `llm/reconcile/resolve-self-contradictory` | pure detect → LLM rewrite (Stop async) | rewrite to self-consistent text |
| 2 | Semantic opposition | `llm/reconcile/resolve-category-2` | LLM (Stop async) | merge / rewrite / retain |
| 3 | Observation vs card text (at principle tier) | `llm/reconcile/resolve-category-3-principle` | LLM (Stop async) | escalate / rewrite |
| 4 | Tier violation | `detect-tier-violation` | pure (Stop) | `:apply-tier` — recompute via `tier/eligible-tier` |
| 5 | Provenance conflict (same born-session + born-context) | `detect-provenance-conflicts` | pure (Stop) | `:merge-candidates` |
| 6 | Contextual override (violated → sustained confirm) | `detect-contextual-override` + `llm/reconcile/resolve-contextual-override` | pure detect → LLM rewrite (Stop async) | scope-qualify rewrite or mark intentional |

**Stop dedup.** `run-pure-reconcile!` skips any `(card-id, category)` pair
that already has an open (unresolved) contradiction record in
`.succession/contradictions/`. This prevents duplicate contradiction files
when Stop fires multiple times against the same card state within a session.
Categories 1, 4, 5, and 6 are all subject to this dedup; the LLM pass for
categories 2 and 3 operates on the same open-contradictions list and is
therefore implicitly deduplicated by the pure pass.

The pure pass writes contradiction records to `store/contradictions` and
stages `:mark-contradiction` deltas so PreCompact can act. The LLM pass is
enqueued as an `:llm-reconcile` job and runs in the drain worker (see
§Async job queue), which marks resolutions via
`store/contradictions/mark-resolved!` when `auto-applicable?` returns true.

## Atomicity model

Four promises the store layer makes:

1. **Observations are append-only.** One file per observation under
   `observations/{session-id}/{ts}-{uuid}.edn`. Never edited, never deleted.
2. **Staging is append-only within a session.** `staging/{session-id}/deltas.jsonl`
   grows until PreCompact clears the session directory.
3. **Cards are rewritten only under the promote lock.** `store/locks/with-lock`
   acquires `.succession/promote.lock` before any card file is touched.
4. **Every promotion is preceded by an archive snapshot.**
   `archive/{ts}/promoted/…` captures the pre-promotion state so
   `identity-diff` can diff any two ticks and rollback is a file copy.

There is exactly one site that rewrites cards: `hook.pre-compact/promote!`.
Every other code path is a reader or an appender. That is why a failing
PreCompact cannot corrupt the promoted tree — the snapshot is already on
disk before `clear-tier-files!` runs.

## Async job queue

The PostToolUse judge lane and the Stop LLM reconcile lane both hand work
off to a single **filesystem-backed job queue** drained by a **single
auto-exiting worker**. The queue lives under
`.succession/staging/jobs/`:

```
staging/jobs/
  <iso-ts>-<uuid>.json        pending — visible to the scanner
  .inflight/<…>.json           claimed by a worker
  dead/<…>.json + <…>.error.edn  handler failures (dead-letter)
  .worker.lock                 at-most-one worker marker
```

**Filenames are the sort key.** ISO-8601 basic timestamp prefix +
UUID suffix means lexicographic == chronological, with a total order
for ties within the same millisecond. FIFO across all job types —
judge and llm-reconcile interleave purely by enqueue time because later
observations reference earlier card state.

**Enqueue is atomic.** `store/jobs/enqueue!` writes `<file>.tmp` then
`Files/move` with `ATOMIC_MOVE`, so scanners never see a half-written
JSON file. Claim (jobs/ → .inflight/) uses the same atomic move primitive,
so two workers cannot both claim the same file.

**At-most-one worker.** `.worker.lock` is created with `Files/createFile`
(atomic, create-if-not-exists). Hooks spawn `succession worker drain`
only when the lock is absent or its mtime is older than
`:stale-lock-seconds` (60s by default, 3× the heartbeat). The worker's
first act is `try-lock-at`; a losing race immediately exits 0.

**Self-exit on idle.** The worker's `domain/queue/idle?` predicate flips
`true` when pending + inflight are both zero and no activity has landed
for `:idle-timeout-seconds`. At that point the scanner closes the jobs
channel, the `pipeline-blocking` drains cleanly, and `run!` releases the
lock and returns.

**1-attempt dead-letter.** A handler exception caught by the pipeline
moves the file to `dead/<name>.json` and writes a sibling
`<stem>.error.edn` capturing class/message/job payload. No automatic
retry — LLM call failures rarely self-heal, and re-queuing a dead job
is a manual `mv`. The only "retry" path is `sweep-stale-inflight!`,
which moves `.inflight/*.json` files older than `:stale-lock-seconds`
back to `jobs/` on worker start. That recovers mid-job crashes without
rewarding handler-level flakiness.

**core.async boundary.** Channels exist in exactly one namespace —
`succession.worker.drain`. `store/jobs` is pure I/O; `domain/queue` is
pure values; the hooks are trivial 3-line enqueue sites. The drain
worker spawns three `a/thread` loops (scanner, heartbeat, idle-watcher)
that feed a `pipeline-blocking` with `:parallelism` lanes (default 2),
so no more than two `claude -p` subprocesses run at once regardless
of queue depth.

Configured via `:worker/async` in `.succession/config.edn`:

| Key | Default | Effect |
|-----|---------|--------|
| `:idle-timeout-seconds` | 10 | worker exits after this long idle |
| `:parallelism` | 2 | concurrent `claude -p` cap |
| `:stale-lock-seconds` | 60 | mtime older than this = dead worker |
| `:heartbeat-seconds` | 20 | lock mtime refresh cadence |
| `:scan-interval-ms` | 500 | jobs-dir poll interval |
| `:dead-letter-enabled` | true | (reserved for future use) |

Source: `src/succession/store/jobs.clj`,
`src/succession/domain/queue.clj`,
`src/succession/worker/drain.clj`.

## Finding 1 in one paragraph

The load-bearing delivery channel is the PostToolUse refresh gate. On
pytest-dev/pytest-5103 with Sonnet 4.6, an identical ~400-byte reminder
delivered via PostToolUse `hookSpecificOutput.additionalContext` produced
**18 productive `replsh eval` calls**; the same rule delivered only via
CLAUDE.md produced **0**. The mechanism is Claude Code's internal
`reorderAttachmentsForAPI`, which bubbles PostToolUse additionalContext to
land adjacent to the most recent frame — inside the attention window where
instructions still influence behavior at depth. The refresh gate's four tuned
parameters (`integration-gap-turns: 2`, `cap-per-session: 5`,
`byte-threshold: 200`, `cold-start-skip-turns: 1`) are imported unchanged
from the experiment. Full methodology and cell tables live in
[docs/archive/succession-findings-2026.md](archive/succession-findings-2026.md).
