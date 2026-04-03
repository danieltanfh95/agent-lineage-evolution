# Imprint v2 — Behavioral Pattern Extraction with Cascading Rules

## Context

**Problem:** Imprint v1 (stashed as `imprint-v1`) simplified SOUL down to a single Stop hook writing rules to CLAUDE.md/AGENTS.md. The assumption that these files stay reliably in context was wrong — there's clear performance regression in longer sessions due to instruction drift at ~150k tokens.

**Goal:** Keep the core pivot to behavioral pattern extraction, but fix enforcement reliability and redesign rule storage as individual files with CSS-like cascading. Also add retrospective transcript analysis for inspecting degraded sessions.

**Key insight:** The only enforcement mechanism immune to instruction drift is **PreToolUse command hooks** — they run mechanically before every tool call, outside the agent's context window. Advisory rules need periodic **re-injection via `additionalContext`** (only command hooks support this). All enforcement logic lives in our scripts, not in Claude Code's `if`/`matcher` syntax (which is just glob filtering, too limited to build on).

---

## Architecture

### Rule Storage: One File Per Rule

Each rule is an individual markdown file with YAML frontmatter:

```
~/.imprint/rules/              # Global rules (all projects)
  no-force-push.md
  read-before-edit.md
.imprint/rules/                # Project rules (override global)
  allow-agents.md
  use-knex.md
.imprint/compiled/             # Generated artifacts (gitignored)
  tool-rules.json              # Compiled mechanical rules for PreToolUse command hook
  semantic-rules.md            # Compiled semantic rules for PreToolUse prompt hook
  advisory-summary.md          # Pre-rendered advisory rules for Stop hook re-injection
```

**Rule file format:**
```markdown
---
id: no-force-push
scope: global                  # global | project
enforcement: mechanical        # mechanical | semantic | advisory
type: correction               # correction | confirmation | preference
source:
  session: abc-123
  timestamp: 2026-04-01T10:00:00Z
  evidence: "User said: never force push"
overrides: []                  # Rule IDs this explicitly cancels
enabled: true
---

Never force-push to any branch without explicit user confirmation.

## Enforcement
- block_bash_pattern: "git push.*(--force|-f)"
- reason: "Force-push blocked — user requires explicit confirmation"
```

### Cascade Resolution (Simple Merge)

No Datalog. The format is designed so a Datalog resolver could be swapped in later without changing rule files.

```
1. Load ~/.imprint/rules/*.md (global)
2. Load .imprint/rules/*.md (project)
3. Project rules with same `id` override global rules
4. Rules with `overrides: [id-1, id-2]` cancel those rules
5. Filter to enabled: true
6. Partition into mechanical / semantic / advisory
7. Compile mechanical → tool-rules.json
8. Compile semantic → semantic-rules.md (included in prompt hook's prompt)
9. Render advisory → advisory-summary.md (for re-injection via additionalContext)
```

### Three Enforcement Tiers

Rules like "block `git push --force`" can be regex-matched. Rules like "use Edit instead of sed for source files" require semantic understanding. Rules like "prefer concise responses" can't be enforced at all — only reminded.

| Tier | How | Cost | Example |
|------|-----|------|---------|
| **Mechanical** | PreToolUse command hook, regex/exact match | $0, ~10-50ms | Block `git push --force`, require read-before-edit |
| **Semantic** | PreToolUse prompt hook (Sonnet) | ~$0.005 | "Is this `sed` editing a source file? Use Edit instead" |
| **Advisory** | Re-injected via `additionalContext` on Stop | $0 | "Prefer concise responses", "ask before large changes" |

**Why Sonnet for semantic checks:** Experiments confirmed Sonnet reasons better than Haiku and actually saves tokens overall (fewer retries/misunderstandings). Sonnet has its own quota in Claude Code, so cost is manageable.

Each rule's `enforcement` frontmatter field determines its tier. The resolve step compiles them into three separate artifacts.

### Hook Architecture (4 hooks)

| Hook | Event | Type | What it does | Cost |
|------|-------|------|-------------|------|
| `imprint-session-start.sh` | SessionStart | command | Cascade resolution, compile all three enforcement artifacts, inject advisory rules via `additionalContext` | $0 |
| `imprint-pre-tool-use.sh` | PreToolUse | command | Read compiled `tool-rules.json`, block violations mechanically | $0, ~10-50ms |
| (inline in settings.json) | PreToolUse | prompt | Sonnet evaluates tool call against compiled semantic rules. Returns `{ok: false, reason}` to block. | ~$0.005 |
| `imprint-stop.sh` | Stop | command | 3-tier correction detection, pattern extraction → write rule files, periodic advisory re-injection via `additionalContext` | $0 most turns, ~$0.01-0.02 on extraction |
| (inline in settings.json) | Stop | prompt | Sonnet audits the completed response against advisory rules (catches things PreToolUse can't, e.g. "don't summarize what you just did") | ~$0.005 |

**Why command hooks for most things:** Command is the only type that can (a) read transcripts, (b) write files, (c) return `additionalContext` for drift-resistant re-injection.

**Why a prompt hook for semantic PreToolUse:** It only needs block/allow (no `additionalContext`), and embedding the check as a prompt hook avoids shell-spawning `claude -p`. Sonnet gets the compiled semantic rules + tool input JSON and makes a judgment call.

**Advisory re-injection (the drift fix):** Every N turns (default 10), the Stop hook includes the compiled `advisory-summary.md` in its `additionalContext` output. This is free (just reading a file) and keeps soft rules in recent context — unlike SessionStart injection which only fires once.

### Retrospective Transcript Analysis

CLI tool for post-hoc extraction from past Claude Code sessions:

```bash
imprint extract <transcript.jsonl>           # Extract rules from a transcript
imprint extract --session <id>               # Auto-find transcript by session ID
imprint extract --last                       # Most recent session
imprint extract --from-turn 42 <transcript>  # Analyze from turn 42 onward ("what went wrong?")
imprint extract --apply                      # Write extracted rules (default: dry run to stdout)
imprint extract --interactive                # Drop into Claude conversation to explore transcript
```

The `--interactive` mode starts a Claude session with the transcript loaded as context, letting you ask questions like "what corrections did the user make?" or "why did performance degrade after turn 50?" and selectively extract rules.

**Implementation:** Shell script that reads JSONL, filters to human/assistant messages, and either:
- Runs extraction prompt via `claude -p` (batch mode)
- Starts `claude` with transcript as context (interactive mode)

### Skill Extraction from Transcripts

A "skill" is a replayable bundle of behavioral patterns + domain knowledge — like a recipe the agent learned from doing a task. Unlike rules (which are constraints/corrections), skills are **positive patterns** — "when doing X, here's how to do it well."

```bash
imprint skill extract <transcript.jsonl>         # Extract skill from a transcript
imprint skill extract --interactive <transcript>  # Interactive exploration first
imprint skill extract --name "deploy-flow"        # Name the extracted skill
```

**What it produces:** A `SKILL.md` file containing:
- **Context:** When this skill applies (trigger conditions)
- **Steps:** The behavioral pattern / workflow observed in the transcript
- **Knowledge:** Domain facts learned during the task
- **Rules:** Any corrections/preferences specific to this skill

**Where skills live:**
```
~/.imprint/skills/<name>/SKILL.md    # Global skills (available everywhere)
.imprint/skills/<name>/SKILL.md      # Project skills (project-specific)
```

Skills are injected via SessionStart `additionalContext` alongside advisory rules. They follow the same cascade: project skills override global skills with the same name.

**Relationship to rules:** Skill extraction uses the same transcript analysis pipeline as rule extraction, but with a different prompt that focuses on "what was the workflow?" rather than "what corrections were made?" Both can run on the same transcript — `imprint extract` for rules, `imprint skill extract` for skills.

---

## Implementation Plan

### Step 1: Rule file infrastructure
- Create `imprint-resolve.sh` (~80 lines) — reads rule files from global + project dirs, applies cascade logic, outputs three compiled artifacts: `tool-rules.json` (mechanical), `semantic-rules.md` (semantic), `advisory-summary.md` (advisory)
- Create `lib.sh` (~50 lines) — shared utilities (logging, model mapping, config reading)

**Key files to port from:**
- `skills/soul/scripts/session-start.sh` — cascade assembly logic (lines 40-120)
- `skills/soul/scripts/lib.sh` — model mapping, logging helpers

### Step 2: PreToolUse enforcement
- Create `imprint-pre-tool-use.sh` (~80 lines) — nearly identical to existing `skills/soul/scripts/pre-tool-use.sh` (78 lines), just change paths from `.soul/invariants/tool-rules.json` to `.imprint/compiled/tool-rules.json`

**Port from:** `skills/soul/scripts/pre-tool-use.sh` (reuse almost entirely)

### Step 3: Stop hook (correction detection + extraction + re-injection)
- Create `imprint-stop.sh` (~300 lines) with three phases:
  1. **Correction detection** — port 3-tier pipeline from `skills/soul/scripts/conscience.sh` lines 240-298
  2. **Pattern extraction** — port from conscience.sh lines 400-500, but modify output to write individual rule files instead of appending to `learned.md`. Each extracted rule gets its own file in `.imprint/rules/` with full frontmatter.
  3. **Advisory re-injection** — every N turns, read `advisory-summary.md` and return it as `additionalContext`. After writing new rules, re-run resolve to update compiled artifacts.

**Port from:** `skills/soul/scripts/conscience.sh` (correction detection + extraction phases)

### Step 4: SessionStart hook
- Create `imprint-session-start.sh` (~120 lines) — run resolve, inject advisory rules via `additionalContext`

**Port from:** `skills/soul/scripts/session-start.sh` (simplified — no SOUL.md, no genome ordering, no skill generation)

### Step 5: Init script + skill
- Create `imprint-init.sh` (~100 lines) — setup dirs, register 4 hooks in settings.json:
  - SessionStart command hook → `imprint-session-start.sh`
  - PreToolUse command hook → `imprint-pre-tool-use.sh` (mechanical)
  - PreToolUse prompt hook with `model: "claude-sonnet-4-6"` — prompt reads compiled `semantic-rules.md` at init time and embeds it. Re-generated by resolve step whenever semantic rules change.
  - Stop command hook → `imprint-stop.sh`
- Create `~/.claude/skills/imprint/SKILL.md` — `/imprint setup`, `/imprint show`, `/imprint review` commands
- Hooks install globally for enforcement; skill provides UX commands

### Step 6: Retrospective CLI
- Create `imprint-extract-cli.sh` (~150 lines) — transcript analysis tool
- Batch mode: read JSONL → filter messages → run extraction prompt → output proposed rules
- Interactive mode: start `claude` with transcript context for exploratory analysis
- `--from-turn N` flag for investigating degradation points

### Step 7: Skill extraction CLI
- Create `imprint-skill-extract.sh` (~150 lines) — transcript → SKILL.md extraction
- Uses same transcript reading infrastructure as `imprint-extract-cli.sh`
- Different extraction prompt: "what workflow/patterns were demonstrated?" vs "what corrections were made?"
- Batch mode outputs SKILL.md to stdout or writes to `~/.imprint/skills/<name>/` with `--apply`
- Interactive mode for exploratory transcript analysis before extraction

### Step 8: Migration + cleanup
- `imprint migrate` command to convert existing `.soul/invariants/learned.md` or CLAUDE.md `## Learned Rules` into individual rule files
- Archive old SOUL scripts to `archive/`
- Update README

---

## What We Drop from SOUL

| Component | Disposition | Reason |
|---|---|---|
| SOUL.md (identity store) | Drop | Focus is behavioral rules, not knowledge |
| Rolling compaction (PostCompact) | Drop | Individual rule files don't need compression |
| Genome ordering (base → language → archetype) | Drop | Simplified to two levels: global → project |
| Conscience audit (LLM auditing LLM) | Keep (redesigned) | Moved from command hook calling `claude -p` → native Stop prompt hook. Sonnet checks completed response against advisory/semantic rules. Cheaper, cleaner. |
| tool-rules.json heuristic generation from invariant text | Keep | Moves into resolve step |
| 3-tier correction detection | Keep | Proven, cheap |
| PreToolUse enforcement | Keep (expanded) | Now two tiers: mechanical (command hook) + semantic (prompt hook) |
| Pattern extraction prompt | Keep (modified) | Outputs individual rule files instead of learned.md |
| Skills generation / export | Keep (redesigned) | Becomes part of retrospective CLI — extract replayable skill bundles from transcripts (see below) |

---

## Verification

1. **Unit tests:** Port `experiments/06-soul-bench/test_hooks.sh` pattern — mock `claude` binary, test each hook in isolation
2. **Cascade test:** Create conflicting global + project rules, verify project wins after resolve
3. **Enforcement test:** Create a `block_bash_pattern` rule, verify PreToolUse blocks matching commands
4. **Re-injection test:** Verify Stop hook returns `additionalContext` with advisory rules every N turns
5. **Extraction test:** Simulate a correction in a transcript, verify a new rule file is created with correct frontmatter
6. **Retrospective test:** Run `imprint extract` on a real past transcript, verify it produces sensible rules
7. **End-to-end:** Install via `imprint-init.sh`, start a Claude Code session, make a correction, verify rule is extracted and enforced in subsequent tool calls

---

## File Summary

| File | Lines (est) | Purpose |
|------|------------|---------|
| `imprint-resolve.sh` | ~80 | Cascade resolution → 3 compiled artifacts |
| `lib.sh` | ~50 | Shared utilities |
| `imprint-pre-tool-use.sh` | ~80 | Mechanical rule enforcement (command hook) |
| `imprint-stop.sh` | ~300 | Correction detection + extraction + re-injection (command hook) |
| `imprint-session-start.sh` | ~120 | Compile rules + inject advisory context (command hook) |
| `imprint-init.sh` | ~100 | One-time setup + register all 4 hooks |
| `imprint-extract-cli.sh` | ~150 | Retrospective rule extraction from transcripts |
| `imprint-skill-extract.sh` | ~150 | Retrospective skill extraction from transcripts |
| `SKILL.md` | ~60 | /imprint commands for UX |
| **Total** | **~1,090** | Down from ~1,800 in SOUL v2 |

Note: The semantic PreToolUse prompt hook and Stop audit prompt hook are configured inline in `settings.json` (no script files). Their prompt text is generated by `imprint-resolve.sh` and embedded during init/resolve.
