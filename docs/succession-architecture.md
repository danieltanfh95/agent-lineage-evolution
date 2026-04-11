# Succession — Technical Architecture

## 1. Problem Statement

LLM coding agents suffer from two forms of behavioral amnesia:

1. **Cross-session amnesia**: Corrections and preferences are forgotten when a session ends. Users repeat the same instructions across sessions.
2. **Intra-session drift**: Instructions injected at session start lose influence as the context window fills (~150k tokens for Claude Opus). The agent gradually stops following rules it acknowledged earlier.

Previous approaches (CLAUDE.md files, AGENTS.md, system prompts) address cross-session amnesia but not intra-session drift — these files are injected once and buried under subsequent context.

## 2. Design Principles

1. **Adjacent-to-now beats front-of-context for mid-session instruction following.**
   Rules delivered at the front of context (SessionStart `additionalContext`,
   or even CLAUDE.md's re-prepend-on-every-call) drift backwards as the current
   frame moves further from position 0. The channel that lands inside the
   model's current attention window is PostToolUse `hookSpecificOutput.additionalContext`,
   which Claude Code's `reorderAttachmentsForAPI` bubbles to land immediately
   after the most recent `tool_result`. Directionally confirmed on
   pytest-dev/pytest-5103: adjacent-to-now refresh produced 18 productive
   `replsh eval` calls where CLAUDE.md-only produced 0. See
   `docs/succession-findings-2026.md` §2 (Finding 1).

2. **Periodic re-injection, not one-shot.** A rule injected once at session
   start is structurally in the failure mode this system is designed to counter.
   Advisory rules must be refreshed periodically — either heavy (reinject bundle
   on byte/turn milestones) or light (refresh reminder on tool-call cadence).

3. **One rule, one file.** Individual rule files enable granular management,
   version control, sharing, and CSS-like cascading. Monolithic rule files
   (CLAUDE.md, learned.md) resist editing and grow unbounded.

4. **Extract from corrections, not from instruction.** The system observes user
   corrections organically rather than requiring explicit rule authoring. This
   captures implicit preferences that users wouldn't think to write down.

5. **Facts vs. actions separation.** Factual knowledge ("the API uses REST")
   and behavioral rules ("never force-push") have different lifecycles and
   enforcement mechanisms. Mixing them in one file leads to corruption during
   compaction or extraction.

**What is no longer a design principle.** An earlier version of this document
listed "mechanical enforcement over context injection" as the primary design
principle. The Apr 2026 conscience-loop experiment contradicted that framing:
the mechanical tier was never the load-bearing win, and it is redundant with
Claude Code's built-in permission system (`settings.json` deny rules,
`--permission-mode`, `disallowed-tools`). Critical-safety patterns are handed
off to the harness's permission layer rather than duplicated in Succession's
compiled `tool-rules.json`. See `docs/succession-findings-2026.md` §4
(Finding 3) for the migration path.

## 3. Architecture Overview

```
┌──────────────────────────────────────────────────────────────────┐
│                      Claude Code Session                          │
│                                                                   │
│  ┌──────────┐ ┌────────────┐ ┌───────────────┐ ┌──────────┐      │
│  │SessionStrt│ │UserPrompt  │ │  PostToolUse  │ │   Stop   │      │
│  │          │ │Submit      │ │               │ │          │      │
│  └────┬─────┘ └──────┬─────┘ └───────┬───────┘ └────┬─────┘      │
│       │              │               │              │            │
│  orient:          top-N rules    ┌─ refresh ─┐    detect          │
│  compile +       relevant to    │ (adjacent  │   corrections      │
│  digest +        this prompt    │  to now)   │  + extract         │
│  advisory                        │            │   rules            │
│  bundle                          ├─ reinject ─┤                    │
│  + skills                        │ (heavy     │                    │
│  (once)                          │  bundle,   │                    │
│                                  │  milestone)│                    │
│                                  ├─ judge ────┤                    │
│                                  │ (observer, │                    │
│                                  │ → jsonl)   │                    │
│                                  └────┬───────┘                    │
│                                       │                            │
│       Permission / safety floor lives in the harness               │
│       (Claude Code settings.json deny rules). Succession            │
│       no longer duplicates it in a PreToolUse tier.                │
└───────┬───────────┬─────────────┬─────────────────┬───────────────┘
        │           │             │                 │
        ▼           ▼             ▼                 ▼
┌──────────────────────────────────────────────────────────────────┐
│                         Rule Storage                             │
│                                                                  │
│  ~/.succession/rules/    ← global (all projects)                 │
│  .succession/rules/      ← project (overrides global)            │
│                                                                  │
│  .succession/compiled/   ← generated artifacts:                  │
│    advisory-summary.md     (reinject bundle content)             │
│    active-rules-digest.md  (judge + reinject snapshot)           │
│    semantic-rules.md       (SessionStart orientation)            │
│                                                                  │
│  .succession/refresh-text.md  ← short adjacent-to-now reminder   │
│                                                                  │
│  .succession/log/                                                │
│    judge.jsonl             (conscience verdicts)                 │
└──────────────────────────────────────────────────────────────────┘
```

## 4. Three Enforcement Tiers + Harness Floor

> **Architectural shift (Apr 2026).** Succession no longer runs a mechanical
> enforcement tier of its own. The Apr 2026 conscience-loop experiment
> established that the mechanical tier was not load-bearing on any tested cell
> and is structurally redundant with Claude Code's built-in permission system.
> The three tiers below (semantic, advisory, conscience judge) carry all of
> Succession's enforcement weight; the critical-safety surface is handed off
> to the Claude Code harness as a migration, not as a layer Succession
> duplicates. See `docs/succession-findings-2026.md` for the evidence and
> migration path.

### Harness floor: Claude Code permission system (not a Succession tier)

Blocking critical destructive operations (`rm -rf /`, `git push --force` to
protected branches, destructive SQL, credential writes) lives in the Claude
Code harness itself via `settings.json` permission deny rules, the
`--permission-mode` flag, and `disallowed-tools`. Projects migrating off
Succession's old mechanical tier should translate their `tool-rules.json`
entries to `settings.json` deny rules. Example:

```json
{
  "permissions": {
    "deny": [
      "Bash(rm -rf /*)",
      "Bash(rm -rf ~*)",
      "Bash(git push*--force*main)"
    ]
  }
}
```

This is strictly more powerful than Succession's old compiled regex blocks:
the harness enforces these before any hook runs, integrates with the
permission-prompt UX, and doesn't need a separate ruleset compilation step.

### Tier 1 — Semantic (UserPromptSubmit classifier + top-N injection)

**Mechanism.** A UserPromptSubmit hook sends the user's prompt + a compact
summary of the rule catalogue to Sonnet. Sonnet returns the IDs of the rules
most relevant to this prompt. The hook then emits the full bodies of the
top-N rules (default 4) as `additionalContext`. The injection lands adjacent
to the prompt the user just asked about — structurally close to the current
frame, not at position 0.

**Example rules this channel carries well:**
- "Use Edit tool instead of sed when modifying source files"
- "Don't create new files unless absolutely necessary — prefer editing existing ones"
- "When explaining code, use analogies to backend concepts (user is a backend developer)"

**Properties.**
- Cost: ~$0.005 per classifier call at Sonnet default sampling
- Latency: ~1-3 s added to the first turn of each user prompt
- Opt-in via `judge.enabled` (the classifier shares the same gate)
- Delivery position: adjacent to the user prompt, not front-of-context

### Tier 2 — Advisory (reinject + refresh)

Succession runs **two complementary channels** for advisory rule delivery,
both through PostToolUse `additionalContext`.

**§2a. Reinject — heavy bundle on byte/turn milestones.** The reinject gate
(`reinject.clj`) fires when transcript bytes grow past `reinject.byteThreshold`
(default 200 KB) **or** turns advance by `reinject.turnThreshold` (default 10)
since the last fire, whichever first. State is per session in
`/tmp/.succession-reinject-state-<sid>`. Both the Stop hook and PostToolUse
hook call through this same gate so the drumbeat fires once per trigger
regardless of which hook trips it. The bundle contains:

1. `advisory-summary.md` — the strong-framing advisory bundle
2. `active-rules-digest.md` — one-line-per-rule compact snapshot
3. The last N judge retrospectives (default 5, controlled by
   `reinject.maxRetrospectives`)

Fire-count cap: default 3 per session, shared `emit-count` cap between
PostToolUse and Stop to bound the headless continuation loop (see §12).

**§2b. Refresh — short reminder on tool-call cadence.** The refresh gate
(`refresh.clj`) fires every `refresh.callInterval` matched tool calls
(default 5) **or** every `refresh.byteThreshold` bytes of transcript growth
(default 40 KB), whichever first, after a `refresh.coldStartSkip` of 5
matched calls. It emits a short `[Conscience]`-prefixed reminder (~400 bytes)
loaded from `$cwd/.succession/refresh-text.md`. State is per session in
`/tmp/.succession-refresh-state-<sid>`, independent of reinject state.

Refresh was added in Apr 2026 on the strength of the attention-drift
experiment: on pytest-dev/pytest-5103, CLAUDE.md-only delivery produced **0
productive `replsh eval` calls** while CLAUDE.md + refresh produced **18**,
via a two-phase adoption curve (trigger → integration → sustainment) where
refresh sustained rule salience through the integration-gap friction zone
until the rule integrated into the working loop. Refresh is the lightest
adjacent-to-now channel and fires most often; reinject is the heavy channel
and fires occasionally. See `docs/succession-findings-2026.md` §2
(Finding 1).

**Why both?** Pure refresh is a short reminder — not enough to carry a full
context rebuild with judge retrospectives and the active-rules digest. Pure
reinject fires on milestones and on typical sessions never fires at all
(see Finding 2 in the findings doc). The two channels cover complementary
cadences.

**Example rules this channel carries well:**
- "Prefer concise responses — don't summarize what you just did"
- "Prefer `replsh eval` over mental tracing when verifying non-trivial behavior"
- "Ask before making large changes"

### Tier 3 — Conscience judge (PostToolUse observer + retrospectives)

**Mechanism.** A PostToolUse hook invokes an LLM judge (Sonnet floor, Opus
escalation on low confidence) against the just-executed tool call plus the
compiled `active-rules-digest.md`. The verdict (followed/violated/ambiguous/
not-applicable + retrospective reasoning + confidence) is appended to
`.succession/log/judge.jsonl`. The Stop hook runs a coarser per-turn pass
over the full set of tool uses since the last human message.

**The judge is an observer, not a gatekeeper.** Verdicts are written to the
log and wait there until the reinject gate fires, at which point recent
retrospectives are bundled into the heavy bundle prefixed
`[Succession conscience]`. The `judge-conscience-framing` advisory rule
tells the main agent how to read them.

**Cost — affordable but currently under-used.** Sonnet is the floor; Haiku
is explicitly disallowed at config-load time. $0.0027 per verdict measured
in the spike. A full session at 50% sampling runs ~$0.03, three orders of
magnitude below the default session budget cap of $0.50. The bottleneck on
the judge's contribution is not cost — it's the delivery path: in every
sweep cell, the single completion finished below the reinject gate's
thresholds, so the judge wrote verdicts but nothing in the main agent's
context ever read them. See `docs/succession-findings-2026.md` §3
(Finding 2).

**Cost control knobs:**
- `judge.mode`: `off | sync | async` (async detaches a bb subprocess that
  outlives the hook return)
- `judge.tools`: default `["Bash" "Edit" "Write" "MultiEdit" "Task"]` —
  Read/Glob/Grep are filtered as cheap and rarely rule-relevant
- `judge.samplingRate`: default `0.5`
- `judge.coldStartSkip`: default 5
- `judge.sessionBudgetUsd`: default `$0.50`
- **Loop guard**: every hook calls `common/require-not-judge-subprocess!`;
  every spawned judge child inherits `SUCCESSION_JUDGE_SUBPROCESS=1`

**Properties:**
- Cost: ~$0.0027 per Sonnet verdict at default sampling
- Latency: sync mode ~18 s/call; async mode ~342 ms parent return, child
  runs detached in background
- Delivery position: verdicts land in the reinject bundle, which itself
  lands adjacent to tool_result — so once the reinject gate fires, judge
  retrospectives are delivered adjacent-to-now, not at position 0.

### §4.5 — Reinject and refresh as peers

Reinject (§2a) and refresh (§2b) are the two advisory delivery channels and
run as peers on PostToolUse. They differ in weight and cadence:

|  | Reinject | Refresh |
|---|---|---|
| Weight | Heavy bundle (advisory summary + digest + judge retrospectives) | Short reminder (~400 bytes, names a specific rule/invocation) |
| Cadence | Milestones (200 KB or 10 turns) | Tool-call interval (every 5 matched calls or 40 KB) |
| Fires/session | 0–3 typical | Many (unbounded by default; cap=5 in the original experiment, now configurable) |
| State | `/tmp/.succession-reinject-state-<sid>` | `/tmp/.succession-refresh-state-<sid>` |
| Primary evidence | Pass-through for judge retrospectives (feedback loop) | 18-0 on pytest-5103 sustaining rule across integration gap |
| Called from | PostToolUse + Stop | PostToolUse only |

Both channels emit via PostToolUse `hookSpecificOutput.additionalContext`,
which Claude Code's `reorderAttachmentsForAPI` bubbles to land immediately
after the most recent `tool_result` — inside the model's current attention
window. This adjacent-to-now property is what makes them distinct from
SessionStart `additionalContext`, which lands once in message history and
drifts backwards with the frame.

**Why both triggers on reinject?** Pure bytes loses the anchoring function
in short sessions with many small turns (the agent never crosses 200 KB but
still drifts). Pure turns loses the high-throughput case where drift bites
hardest — long plan-mode reads that burn context without advancing the turn
counter. The hybrid `max(bytes, turns)` covers both.

## 5. Correction Detection Pipeline

The Stop hook runs three tiers of correction detection on every turn:

```
Turn N: user says "no, don't do that"
  │
  ├── Tier 1: Keyword scan (free)
  │   Stop words: "no,", "don't", "stop", "instead", "wrong"...
  │   Match? → continue to Tier 2
  │
  ├── Tier 2: Sonnet micro-prompt (~$0.005)
  │   "Is this user message correcting agent behavior? YES/NO"
  │   YES? → set correction flag, continue
  │
  └── Tier 3: Extraction threshold reduction
      Correction flag reduces the extraction threshold by 5x
      (from ~80KB to ~16KB of transcript growth)
      Next extraction will include this correction context
```

When the extraction threshold is reached:

```
  ├── Read transcript window (up to 200KB)
  ├── Send to Sonnet with extraction prompt
  ├── For each extracted rule:
  │   ├── Classify: mechanical / semantic / advisory
  │   ├── Write individual rule file with frontmatter
  │   └── Log to extractions.jsonl
  └── Re-run cascade resolution (update compiled artifacts)
```

## 6. Four Knowledge Categories

Each rule is classified into one of four knowledge categories, inspired by what memory systems miss:

| Category | What it captures | Example |
|----------|-----------------|---------|
| **Strategy** | How the agent approaches problems — workflow patterns, methodologies | "Always plan before editing" |
| **Failure inheritance** | Patterns of failure to avoid — anti-patterns, things that went wrong | "Never force-push without confirmation" |
| **Relational calibration** | Communication style — tone, verbosity, explanation depth | "Prefer concise responses, don't summarize" |
| **Meta-cognition** | Which heuristics proved reliable vs. which sounded plausible but failed | "Sonnet saves tokens over Haiku for semantic checks" |

Rules without a `category:` field default to `strategy` for backwards compatibility.

## 7. Effectiveness Tracking

Every rule carries effectiveness counters in its frontmatter:

```yaml
effectiveness:
  times_followed: 18
  times_violated: 2
  times_overridden: 0
  last_evaluated: 2026-04-01T10:00:00Z
```

**How tracking works:**
- **Judge verdicts**: The PostToolUse conscience judge writes
  `rule_followed` / `rule_violated` verdicts to `.succession/log/judge.jsonl`
  for each sampled tool call, against the rule the digest identifies as the
  most-relevant.
- **Correction matching**: When a user correction is detected, Sonnet checks
  if it matches an existing rule. If yes, `rule_violated` is logged against
  that rule instead of creating a duplicate.
- **Counter materialization**: The Stop hook periodically reads the JSONL
  log and updates frontmatter counters in rule files.

**Promotion and review:** During cascade resolution, rules are flagged:
- Rules with >50% violation rate (10+ evaluations) → `review-candidates.json`
  with action `review`
- Advisory rules with >80% follow rate (10+ evaluations) →
  `review-candidates.json` with action `promote` (candidate for refresh-text
  elevation)

## 8. Cascade Resolution

Rules cascade from global to project level, with project rules taking precedence:

```
1. Load ~/.succession/rules/*.md (global scope)
2. Load .succession/rules/*.md (project scope)
3. For rules with the same `id`: project wins
4. For rules with explicit `overrides: [id]`: remove overridden rules
5. Filter to enabled: true
6. Partition by enforcement tier
7. Compile to artifacts:
   - semantic-rules.md (SessionStart orientation bundle)
   - advisory-summary.md (reinject bundle content)
   - active-rules-digest.md (judge + reinject snapshot)
```

Rules authored with `enforcement: mechanical` are still parseable for
backwards compatibility but are treated as advisory — the mechanical
tier itself is no longer wired (see §4).

**Why not Datalog?** The actual conflict scenarios are simple: two-level cascade with explicit overrides. Datalog would add ~500 LOC + a runtime dependency for the same result that ~30 lines of bash+jq achieves. The rule file format is designed to be Datalog-compatible if needed later.

## 9. Retrospective Transcript Analysis

Succession can extract rules from past sessions post-hoc:

```bash
succession-extract-cli.sh --last                    # Most recent session
succession-extract-cli.sh --from-turn 42 <file>     # "What went wrong after turn 42?"
succession-extract-cli.sh --interactive <file>       # Explore transcript interactively
```

The batch mode runs the same extraction prompt used by the Stop hook but adds:
- **Degradation analysis**: identifies turns where agent behavior noticeably worsened
- **Possible causes**: "context window filled with code output", "correction was acknowledged but not internalized"

Interactive mode starts a Claude session with the transcript loaded, letting users ask questions and selectively extract rules.

## 10. Skill Extraction

A "skill" is a replayable bundle of behavioral patterns + domain knowledge:

```bash
succession-skill-extract.sh --last --apply --name "deploy-flow"
```

A SKILL.md contains:
- **Context**: trigger conditions (when to activate this skill)
- **Steps**: the observed workflow pattern
- **Knowledge**: domain facts learned during the task
- **Rules**: task-specific corrections and preferences

Skills follow the same cascade as rules: project skills override global skills with the same name. They are injected via SessionStart alongside advisory rules.

## 11. Comparison with Prior Systems

| | ALE (2025) | SOUL v2 (2026) | Succession (2026) |
|---|---|---|---|
| **Core idea** | Episodic succession | Continuous governance | Behavioral extraction |
| **Identity storage** | Handoff package | SOUL.md (monolithic) | None (rules only) |
| **Rule storage** | None | invariants/*.md | One file per rule |
| **Enforcement** | N/A | Conscience audit (LLM) | Semantic + advisory (reinject/refresh) + conscience judge; harness handles critical-safety floor |
| **Drift mitigation** | N/A | SessionStart injection (once) | Adjacent-to-now refresh + hybrid reinject + judge retrospectives |
| **Knowledge compaction** | Handoff summarization | LSM-tree rolling compaction | Not needed (individual files) |
| **Cascade** | N/A | Genome hierarchy (4 levels) | Two levels (global → project) |
| **Hooks** | None | 4 (SessionStart, Stop, PostCompact, PreToolUse) | 4 command hooks (SessionStart, UserPromptSubmit, PostToolUse, Stop) |
| **Retrospective analysis** | N/A | /soul review (recent) | Full transcript analysis + skill extraction |
| **Cost per session** | N/A | ~$0.50-2.00 (Sonnet audit loop) | ~$0.05-0.20 (extraction only) |

## 12. Limitations and Future Work

- **Headless Stop-hook continuation loop (unresolved)**: `B-all` cells in the
  Apr 2026 sweep produced 7-11 `result` entries in `driver.json` instead of 1
  in `claude -p` mode. The `reinject.clj` emission cap (3 per session) halved
  the cost but did not eliminate it — an unidentified `claude -p` internal
  re-entry path is still firing. Any change wiring Stop + reinject in headless
  mode has unknown cost tails until this is understood. See
  `docs/succession-findings-2026.md` §7 (open question 1).
- **Refresh layer has n=1 validation**: The 18-0 pytest-5103 result is
  directional, not statistical. Replication on a second hard instance
  (pytest-11143 is the next candidate) is needed before refresh is load-bearing
  enough to be a hard default.
- **Refresh text sourcing is per-project**: No global default from
  `~/.succession/`, no auto-compilation from rules. Each project drops its
  own `refresh-text.md`. Auto-compilation from the highest-priority advisory
  rule's body at SessionStart is a sensible follow-up.
- **Refresh text content confound**: The refresh reminder in the experiment
  explicitly names the concrete `replsh eval --name swebench '<expr>'`
  invocation. A reminder that only said "verify your assumptions" may
  produce less uplift. Not yet ablated.
- **Judge correctness is not ground-truthed**: Verdicts are well-formed JSON
  and cost-bounded, but "was this verdict actually correct about the rule"
  is unmeasured.
- **Advisory rules still drift on very long sessions**: Periodic re-injection
  mitigates but doesn't eliminate drift. The refresh layer sustains salience
  across the integration gap; sustainment past tens of thousands of turns
  has not been tested.
- **No cross-rule inference**: Rules are independent. "If React then
  TypeScript" + "If TypeScript then strict mode" does not produce "If
  React then strict mode." A Datalog resolver could add this.
- **Claude Code only**: Hooks are specific to Claude Code's hook system,
  and the adjacent-to-now property depends on Claude Code's
  `reorderAttachmentsForAPI` bubbling PostToolUse `additionalContext` to
  land next to `tool_result`. Supporting other harnesses requires adapting
  both the hook interface and the attachment-ordering assumption.
- **Extraction quality depends on Sonnet**: If the extraction model
  misclassifies a rule's enforcement tier, the wrong channel is used.
  User review (`/succession review`) mitigates this.
