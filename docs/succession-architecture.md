# Succession — Technical Architecture

## 1. Problem Statement

LLM coding agents suffer from two forms of behavioral amnesia:

1. **Cross-session amnesia**: Corrections and preferences are forgotten when a session ends. Users repeat the same instructions across sessions.
2. **Intra-session drift**: Instructions injected at session start lose influence as the context window fills (~150k tokens for Claude Opus). The agent gradually stops following rules it acknowledged earlier.

Previous approaches (CLAUDE.md files, AGENTS.md, system prompts) address cross-session amnesia but not intra-session drift — these files are injected once and buried under subsequent context.

## 2. Design Principles

1. **Mechanical enforcement over context injection.** Rules that can be expressed as tool-call predicates (block a bash pattern, require read-before-edit) should be enforced mechanically, outside the agent's context window. This is immune to instruction drift.

2. **Periodic re-injection for soft rules.** Rules that require semantic understanding (advisory preferences) must be periodically refreshed in the agent's context via `additionalContext`, not injected once.

3. **One rule, one file.** Individual rule files enable granular management, version control, sharing, and CSS-like cascading. Monolithic rule files (CLAUDE.md, learned.md) resist editing and grow unbounded.

4. **Extract from corrections, not from instruction.** The system observes user corrections organically rather than requiring explicit rule authoring. This captures implicit preferences that users wouldn't think to write down.

5. **Facts vs. actions separation.** Factual knowledge ("the API uses REST") and behavioral rules ("never force-push") have different lifecycles and enforcement mechanisms. Mixing them in one file leads to corruption during compaction or extraction.

## 3. Architecture Overview

```
┌─────────────────────────────────────────────────────┐
│                   Claude Code Session                │
│                                                      │
│  ┌──────────┐   ┌──────────┐   ┌──────────────────┐ │
│  │ SessionSt│   │PreToolUse│   │      Stop         │ │
│  │ (command)│   │(cmd+pmpt)│   │    (cmd+pmpt)     │ │
│  └────┬─────┘   └────┬─────┘   └────┬──────────────┘ │
│       │              │              │                 │
│  inject rules   block/allow    detect corrections    │
│  + skills       mechanically   extract rules          │
│  (once)         + semantically  re-inject advisory    │
│                 (every call)    (every N turns)        │
└───────┬──────────────┬──────────────┬─────────────────┘
        │              │              │
        ▼              ▼              ▼
┌───────────────────────────────────────────────────────┐
│                    Rule Storage                        │
│                                                        │
│  ~/.succession/rules/    ← global (all projects)          │
│  .succession/rules/      ← project (overrides global)     │
│                                                        │
│  .succession/compiled/   ← generated artifacts:           │
│    tool-rules.json      (mechanical enforcement)       │
│    semantic-rules.md    (semantic prompt hook)          │
│    advisory-summary.md  (re-injection content)         │
└───────────────────────────────────────────────────────┘
```

## 4. Three Enforcement Tiers

### Tier 1: Mechanical (PreToolUse command hook)

**Mechanism:** A bash script reads `tool-rules.json` and evaluates tool calls against regex patterns and exact-match rules. Exits with code 2 to block.

**Rule types:**
- `block_tool: Agent` — block a named tool entirely
- `block_bash_pattern: "git push.*(--force|-f)"` — block bash commands matching regex
- `require_prior_read: true` — block Edit calls unless Read of the target file appears in recent transcript

**Properties:**
- Cost: $0 (no LLM calls)
- Latency: ~10-50ms
- Deterministic: same input always produces same output
- Drift-immune: runs outside the agent's context window
- Limitation: can only match on tool name and primary argument (command string, file path)

### Tier 2: Semantic (PreToolUse prompt hook)

**Mechanism:** A Claude Code prompt hook sends the tool call + compiled semantic rules to Sonnet. Sonnet returns `{ok: true/false, reason: "..."}`.

**Example rules:**
- "Use Edit tool instead of sed when modifying source files"
- "Don't create new files unless absolutely necessary — prefer editing existing ones"
- "Check if a test exists before modifying a function"

**Properties:**
- Cost: ~$0.005 per evaluation (Sonnet, from Claude Code's quota)
- Latency: ~1-3s
- Non-deterministic: LLM-based judgment
- Drift-immune: runs outside the agent's context window
- Limitation: can only evaluate the current tool call, not the broader conversation

### Tier 3: Advisory (Stop hook re-injection)

**Mechanism:** Every N turns (default 10), the Stop hook reads `advisory-summary.md` and returns it as `additionalContext`. This text is injected into the agent's context, refreshing rules that might have drifted.

**Example rules:**
- "Prefer concise responses — don't summarize what you just did"
- "Ask before making large changes"
- "When explaining code, use analogies to backend concepts (user is a backend developer)"

**Properties:**
- Cost: $0 (just reading a file)
- Drift-resistant: refreshed periodically, not once
- Limitation: still in-context, can be overshadowed by very large context

Additionally, a Stop prompt hook audits completed responses against advisory rules, catching violations that PreToolUse cannot (e.g., "don't add trailing summaries").

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

## 6. Cascade Resolution

Rules cascade from global to project level, with project rules taking precedence:

```
1. Load ~/.succession/rules/*.md (global scope)
2. Load .succession/rules/*.md (project scope)
3. For rules with the same `id`: project wins
4. For rules with explicit `overrides: [id]`: remove overridden rules
5. Filter to enabled: true
6. Partition by enforcement tier
7. Compile to three artifacts:
   - tool-rules.json (mechanical)
   - semantic-rules.md (semantic)
   - advisory-summary.md (advisory)
```

**Why not Datalog?** The actual conflict scenarios are simple: two-level cascade with explicit overrides. Datalog would add ~500 LOC + a runtime dependency for the same result that ~30 lines of bash+jq achieves. The rule file format is designed to be Datalog-compatible if needed later.

## 7. Retrospective Transcript Analysis

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

## 8. Skill Extraction

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

## 9. Comparison with Prior Systems

| | ALE (2025) | SOUL v2 (2026) | Succession (2026) |
|---|---|---|---|
| **Core idea** | Episodic succession | Continuous governance | Behavioral extraction |
| **Identity storage** | Handoff package | SOUL.md (monolithic) | None (rules only) |
| **Rule storage** | None | invariants/*.md | One file per rule |
| **Enforcement** | N/A | Conscience audit (LLM) | Mechanical + semantic + advisory |
| **Drift mitigation** | N/A | SessionStart injection (once) | Periodic re-injection + mechanical enforcement |
| **Knowledge compaction** | Handoff summarization | LSM-tree rolling compaction | Not needed (individual files) |
| **Cascade** | N/A | Genome hierarchy (4 levels) | Two levels (global → project) |
| **Hooks** | None | 4 (SessionStart, Stop, PostCompact, PreToolUse) | 3 scripts + 2 prompt hooks |
| **Retrospective analysis** | N/A | /soul review (recent) | Full transcript analysis + skill extraction |
| **Cost per session** | N/A | ~$0.50-2.00 (Sonnet audit loop) | ~$0.05-0.20 (extraction only) |

## 10. Limitations and Future Work

- **Prompt hooks can't return `additionalContext`**: Only command hooks can inject context back. This means the semantic PreToolUse and Stop audit hooks can only block/allow, not guide.
- **Advisory rules still drift**: Periodic re-injection mitigates but doesn't eliminate drift. Very long sessions (>200k tokens) may still lose advisory rules.
- **No cross-rule inference**: Rules are independent. "If React then TypeScript" + "If TypeScript then strict mode" does not produce "If React then strict mode." A Datalog resolver could add this.
- **Claude Code only**: Hooks are specific to Claude Code's hook system. Supporting other agents requires adapting the hook interface.
- **Extraction quality depends on Sonnet**: If the extraction model misclassifies a rule's enforcement tier, the wrong enforcement mechanism is used. User review (`/succession review`) mitigates this.
