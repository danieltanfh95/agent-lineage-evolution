# SOUL Architecture — Events, Triggers, and Data Flow

*Reference document for reviewing the complete SOUL event model. Every hook, what triggers it, what data it receives, what it does, what it writes, and what it costs.*

---

## Event Model Overview

```
┌──────────────────────────────────────────────────────────────────────┐
│                        Claude Code Session                            │
│                                                                        │
│  User message ──→ Agent response ──→ Stop hook fires                  │
│       │                                    │                           │
│       │                     ┌──────────────┼──────────────┐           │
│       │                     │              │              │           │
│       │              Conscience audit  Correction    Pattern          │
│       │              (Haiku, N turns/  detection    extraction        │
│       │               keywords, with   (stop words  (Sonnet, ~20k    │
│       │               transcript ctx)   → Haiku)     tokens or flag)  │
│       │                     │              │              │           │
│       │               block/allow     flag for      ┌────┴─────┐    │
│       │               + systemMessage  extraction   │          │    │
│       │                                         SOUL.md    learned.md│
│       │                                    + staging log             │
│       │                                                              │
│  /compact ──→ PostCompact hook fires                                 │
│                      │                                                │
│               Compaction (Sonnet)                                     │
│               + bullet validation                                     │
│               + diff saving                                           │
│                      │                                                │
│               .soul/SOUL.md (compressed)                             │
│               git add (stage only, no commit)                        │
│               → notification relayed via temp file                   │
│                                                                        │
│  Session start ──→ SessionStart hook fires                           │
│                      │                                                │
│               Genome assembly (no LLM call)                          │
│               Log rotation + session_start event                     │
│               → injected as additionalContext                        │
│                                                                        │
│  All events → .soul/log/soul-activity.jsonl (unified audit trail)    │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Hook: SessionStart

**Script:** `.soul/hooks/session-start.sh`

**Triggers:** Every new session, resume, `/clear`, post-compaction restart.

**Input (stdin JSON):**
| Field | Type | Description |
|---|---|---|
| `session_id` | string | Unique session identifier |
| `cwd` | string | Working directory |
| `source` | string | `"startup"` / `"resume"` / `"clear"` / `"compact"` |
| `model` | string | Active model name |

**Action:**
1. Source shared library from `.soul/hooks/lib.sh`
2. Rotate `.soul/log/soul-activity.jsonl` if >1MB
3. Read genome order from `.soul/config.json` (`genome.order` array)
4. Load genome fragments from `~/.soul/genome/` in configured order (base → learned → language → archetype)
5. Append repo soul from `.soul/SOUL.md`
6. Append all invariants from `.soul/invariants/*.md`
7. Append conflict resolution note (repo-level wins)
8. Parse `## Skills` section of SOUL.md → generate `.claude/skills/<name>/SKILL.md` files
9. Load genome-level skills from `~/.soul/genome/skills/` (unless overridden by repo-level skills)
10. Log `session_start` event with genomes loaded, context size, and skills generated

**Output (stdout JSON):**
```json
{
  "hookSpecificOutput": {
    "hookEventName": "SessionStart",
    "additionalContext": "<assembled genome + soul + invariants>"
  }
}
```

**Writes to:**
- `.claude/skills/<name>/SKILL.md` (overwritten every session — from `## Skills` declarations)
- `.claude/skills/<name>/SKILL.md` (copied from `~/.soul/genome/skills/<name>/` for genome-level skills not overridden by repo)
- `.soul/log/soul-activity.jsonl` — `session_start` event

**Cost:** Zero. No LLM call. File reads only.

**Known gaps:**
- Does not validate that SOUL.md is well-formed
- Does not check genome fragment sizes or total assembled context size
- Skill generation silently fails if SOUL.md has no `## Skills` section (acceptable)

---

## Hook: Stop (Conscience + Pattern Extraction)

**Script:** `.soul/hooks/conscience.sh`

**Triggers:** Every agent response (automatic, cannot be disabled per-response).

**Input (stdin JSON):**
| Field | Type | Description |
|---|---|---|
| `session_id` | string | Unique session identifier |
| `cwd` | string | Working directory |
| `transcript_path` | string | Path to full session transcript file |
| `last_assistant_message` | string | Full text of agent's last response |
| `stop_hook_active` | boolean | `true` if this is a re-fire after a block |
| `permission_mode` | string | Current permission mode |

**Guard:** If `stop_hook_active == true`, exit immediately (prevents infinite loops).

### Phase 1: Conscience Audit

**Trigger condition:** Every N turns (default 5) OR keyword match (`commit`, `delete`, `deploy`, `push`, `force`, `drop`, `remove`, `destroy`).

**Action:**
1. Increment turn counter (`/tmp/.soul-turns-${SESSION_ID}`)
2. Check if Nth turn or keyword match
3. If yes: collect all invariants from `.soul/invariants/*.md`
4. Extract last N turns of conversation context from transcript (configurable via `conscience.contextTurns`, default 10, capped at 20KB)
5. Send `last_assistant_message` + conversation context + invariants to `claude -p` (Haiku)
6. Parse response: `{violated: bool, invariant: string, reason: string}`
7. If violated: return `{"decision": "block", "reason": "..."}` (blocks agent response)
8. If violation count >= kill threshold (default 3): add "session should be restarted" to block reason
9. If audit passes on keyword-triggered turn: surface informational `systemMessage`
10. Log `audit_pass` or `audit_violation` event

**Cost:** ~$0.002 per Haiku audit call (slightly larger with transcript context). Zero on non-audit turns.

### Phase 2: Three-Tier Correction Detection

**Trigger condition:** Every turn (Tier 1 is free).

**Action:**
1. **Tier 1 — Stop word scan (free):** Check last 3 human messages from transcript for correction markers (`"no"`, `"don't"`, `"stop"`, `"instead"`, `"wrong"`, `"not what I"`, configurable). If no match, skip remaining tiers.
2. **Tier 2 — Haiku micro-prompt (~$0.0005):** Send the user message to Haiku with a ~100-token prompt: "Is this user message correcting the agent? YES/NO." Filters false positives like "no, that looks perfect."
3. **Tier 3 — Flag for extraction:** If Haiku confirms a correction, write `/tmp/.soul-correction-flag-${SESSION_ID}`. Pattern extraction (Phase 3) checks for this flag and lowers its threshold by 5x, triggering extraction ~5x sooner after a confirmed correction.
4. Log `correction_tier1` and `correction_tier2` events.

**Cost:** Tier 1 is free. Tier 2 fires only when stop words match (~$0.0005 per Haiku call). Tier 3 is free (flag file).

### Phase 3: Pattern Extraction

**Trigger condition:** Transcript file has grown by >= ~80KB (~20k tokens, configurable) since last extraction. Threshold reduced 5x when a correction flag is present.

**Action:**
1. Check transcript file size vs `/tmp/.soul-extract-offset-${SESSION_ID}`
2. If correction flag exists, divide threshold by 5 and consume the flag
3. If growth < threshold: skip
4. Read transcript window (last offset → current end, capped at 200KB)
5. Read current SOUL.md and genome files
6. Send to `claude -p` (Sonnet) with extraction prompt
7. Parse response: structured JSON with patterns, soul_updates, genome_updates
8. Append repo-specific patterns to `.soul/SOUL.md` (Accumulated Knowledge / Predecessor Warnings sections)
9. Append cross-project patterns to `~/.soul/genome/learned.md`
10. Log extraction details to `.soul/staging/recent-extractions.jsonl` (for `/soul review`)
11. Update offset tracking file
12. Log `extraction` event to unified activity log
13. If SOUL.md was modified: auto-export all skills via `export-skill.sh --quiet`, log `skill_export` events
14. Surface `systemMessage` notification: "Extracted N patterns, applied to SOUL.md -- /soul review to inspect"

**Cost:** ~$0.02 per Sonnet extraction call + ~$0.02 per skill for auto-export. Fires roughly every 20k tokens of conversation (more frequently after corrections).

**Output (stdout JSON):**
```json
{"decision": "block", "reason": "..."}
```
or (non-blocking informational):
```json
{"systemMessage": "SOUL: Extracted 3 patterns, applied to SOUL.md -- /soul review to inspect"}
```
or exit 0 (silent, no output).

Also picks up pending compaction notifications from `/tmp/.soul-compact-notify-${SESSION_ID}` (relayed from PostCompact hook) and surfaces them via `systemMessage`.

**Writes to:**
- `.soul/SOUL.md` — appends new bullets to Accumulated Knowledge and Predecessor Warnings
- `~/.soul/genome/learned.md` — appends cross-project patterns (created on first extraction)
- `.soul/exports/<name>/SKILL.md` — re-exported with fresh knowledge (when SOUL.md modified)
- `.soul/staging/recent-extractions.jsonl` — extraction log for `/soul review`
- `.soul/log/soul-activity.jsonl` — unified audit trail
- `/tmp/.soul-turns-${SESSION_ID}` — turn counter
- `/tmp/.soul-violations-${SESSION_ID}` — violation counter
- `/tmp/.soul-extract-offset-${SESSION_ID}` — byte offset for extraction window
- `/tmp/.soul-correction-flag-${SESSION_ID}` — correction detection flag

**Known gaps:**
- **Token count is estimated.** ~4 chars/token is a rough heuristic. Actual tokenization varies by content.
- **No deduplication across extractions** beyond byte offset tracking. If the same correction appears in two windows, it may be extracted twice. The next compaction cycle (Sonnet) should deduplicate.
- **Extraction and conscience are sequential.** Both run in the same Stop hook invocation. If conscience blocks, pattern extraction still runs (it doesn't affect the block/allow decision). This is correct behavior — we want to learn from turns that get blocked.
- **Correction detection has false negatives.** Stop words may miss corrections phrased without common correction keywords (e.g., "actually, I meant..."). The three-tier pipeline catches common patterns but is not exhaustive.

---

## Hook: PostCompact

**Script:** `.soul/hooks/compact.sh`

**Triggers:** Manual `/compact` command, or auto-compact when Claude Code hits context limit.

**Input (stdin JSON):**
| Field | Type | Description |
|---|---|---|
| `session_id` | string | Unique session identifier |
| `cwd` | string | Working directory |
| `transcript_path` | string | Path to full session transcript file |
| `trigger` | string | `"manual"` or `"auto"` |

**Action (two phases + staging):**

Phase 1 — SOUL.md compaction:
1. Read last 100 transcript entries (assistant + tool_result messages, capped at 8000 chars)
2. Read last 10 git commits
3. Read current SOUL.md and count bullets per section (before snapshot)
4. Send all to `claude -p` (Sonnet, configurable) with compaction prompt
5. Basic validation: must be >50 chars, must contain `## Identity`
6. **Semantic validation:** Count bullets per section in result. If any section lost >50% bullets (configurable via `compaction.maxBulletLossPercent`), reject and save to `.soul/staging/rejected-compaction.md`
7. Save diff to `.soul/staging/last-compaction-diff.txt`
8. If `compaction.requireApproval` is true: write to `.soul/staging/pending-compaction.md` (user approves via `/soul approve-compaction`). If false: overwrite `.soul/SOUL.md` directly.
9. Log `compaction` event with before/after sizes and bullet counts

Phase 2 — Genome compaction (conditional):
1. Check if `~/.soul/genome/learned.md` exists and exceeds 5000 chars
2. If yes: send to `claude -p` (same model) with genome compaction prompt
3. Prompt: deduplicate, merge related patterns, remove project-specific entries, organize by theme
4. Validate: must have content and "Learned Patterns" header
5. Overwrite `learned.md` with compressed version
6. Log `genome_compaction` event

Final: If `autoCommit: true`, stage `.soul/SOUL.md` and `.soul/exports/` via `git add` (stage only — no commit, no `--no-verify`). Relay notification to next Stop hook via `/tmp/.soul-compact-notify-${SESSION_ID}`.

**Writes to:**
- `.soul/SOUL.md` — overwritten with compressed version (unless requireApproval is true)
- `~/.soul/genome/learned.md` — overwritten with compressed version (when over threshold)
- `.soul/staging/last-compaction-diff.txt` — diff of compaction
- `.soul/staging/pending-compaction.md` — pending approval (when requireApproval is true)
- `.soul/staging/rejected-compaction.md` — rejected result (when bullet validation fails)
- `.soul/log/soul-activity.jsonl` — compaction events
- `/tmp/.soul-compact-notify-${SESSION_ID}` — notification relayed to Stop hook
- Git staging area (if autoCommit enabled — `git add` only, no commit)

**Cost:** ~$0.02-0.05 per Sonnet compaction call for SOUL.md. Additional ~$0.02 for genome compaction when triggered. Fires once per `/compact` or auto-compact.

**Known limitations:**
- **Compaction does not extract new patterns.** It only compresses existing SOUL.md content + recent transcript into a smaller SOUL.md. If a correction was in the transcript but not yet in SOUL.md (because pattern extraction hasn't fired), compaction may miss it. Pattern extraction and compaction are independent pipelines. This is why pattern extraction must fire on a tighter cadence than compaction.
- **Transcript window is small.** Only the last 100 entries / 8000 chars of transcript are read. In a 500k-token session, this is a narrow window. Most session context is lost to compaction — only what's already in SOUL.md or in the most recent transcript window survives.
- **Haiku cannot do compaction.** Haiku 4.5 copies instead of compressing — memory balloons to ~28k chars after 50 sessions vs ~5.8k for Sonnet 4.6. This is a confirmed finding (experiments 05-06). The config defaults to Sonnet.
- **PostCompact stdout is discarded.** Compaction notifications cannot be shown directly — they relay through the next Stop hook invocation via a temp file.

---

## Hook: PreToolUse (not currently used by SOUL)

**Triggers:** Before every tool call (Read, Edit, Bash, etc.)

**Input (stdin JSON):**
| Field | Type | Description |
|---|---|---|
| `session_id` | string | |
| `transcript_path` | string | |
| `cwd` | string | |
| `tool_name` | string | Which tool is about to be called |
| `tool_input` | object | The tool's parameters |

**Could be used for:** Pre-flight checks (e.g., "is the agent about to edit a file it hasn't read?"), but not currently implemented. The conscience audit covers this retrospectively.

---

## Hook: PostToolUse (not currently used by SOUL)

**Triggers:** After every tool call completes.

**Input (stdin JSON):**
Same as PreToolUse, plus:
| Field | Type | Description |
|---|---|---|
| `tool_response` | object | The tool's return value |

**Could be used for:** Observing what tools the agent actually used (vs what it said it would do), tracking edit patterns, monitoring for secrets in tool output. Not currently implemented.

---

## Configuration Reference

**File:** `.soul/config.json`

```json
{
  "conscience": {
    "model": "haiku",
    "auditEveryNTurns": 5,
    "alwaysAuditKeywords": ["commit", "delete", "deploy", "push", "force", "drop", "remove", "destroy"],
    "killAfterNViolations": 3,
    "contextTurns": 10,
    "correctionDetection": true,
    "correctionKeywords": ["no", "don't", "stop", "instead", "wrong", "not what I"]
  },
  "genome": {
    "order": ["base", "learned"]
  },
  "compaction": {
    "model": "sonnet",
    "suggestAtPercent": 15,
    "autoCommit": true,
    "requireApproval": false,
    "maxBulletLossPercent": 50
  },
  "patterns": {
    "model": "sonnet",
    "extractEveryKTokens": 20,
    "promoteToCrossProject": true
  }
}
```

| Section | Key | Default | Description |
|---|---|---|---|
| conscience | model | `"haiku"` | Model for invariant audits and correction detection |
| conscience | auditEveryNTurns | `5` | Full audit frequency |
| conscience | alwaysAuditKeywords | (see above) | Keywords that force immediate audit |
| conscience | killAfterNViolations | `3` | Session-restart threshold |
| conscience | contextTurns | `10` | Number of transcript turns sent to Haiku for context |
| conscience | correctionDetection | `true` | Enable three-tier correction detection pipeline |
| conscience | correctionKeywords | (see above) | Tier 1 stop words for correction detection |
| genome | order | `["base"]` | Genome fragment load order |
| compaction | model | `"sonnet"` | Model for SOUL.md compression |
| compaction | suggestAtPercent | `15` | Status line yellow threshold (% of context) |
| compaction | autoCommit | `true` | Auto-stage after compaction (git add only, no commit) |
| compaction | requireApproval | `false` | Require user approval via `/soul approve-compaction` |
| compaction | maxBulletLossPercent | `50` | Reject compaction if any section loses more than this % of bullets |
| patterns | model | `"sonnet"` | Model for pattern extraction |
| patterns | extractEveryKTokens | `20` | Extraction frequency (~tokens), lowered 5x when correction detected |
| patterns | promoteToCrossProject | `true` | Write cross-project patterns to genome |

---

## Data Flow: Complete Session Lifecycle

### 1. Session Starts
```
SessionStart hook →
  Rotate soul-activity.jsonl if > 1MB
  Read ~/.soul/genome/base.md
  Read ~/.soul/genome/learned.md     ← includes patterns from past sessions
  Read .soul/SOUL.md
  Read .soul/invariants/*.md
  Generate .claude/skills/*/SKILL.md  ← from ## Skills declarations
  Load ~/.soul/genome/skills/*/       ← genome skills (repo overrides genome)
  Log session_start event (genomes, context size, skills)
  → Inject as additionalContext
```

### 2. Agent Works (loop)
```
User message → Agent response → Stop hook →
  Increment turn counter
  Check for compaction notification relay (from PostCompact)
  Phase 1 — Conscience:
    IF Nth turn or keyword match:
      Extract last ~10 turns from transcript
      Conscience audit (Haiku) with conversation context → block or allow
      → systemMessage if keyword audit passed
  Phase 2 — Correction detection:
    Tier 1: Stop word scan on last 3 user messages (free)
    IF match → Tier 2: Haiku micro-prompt "Is this a correction?" (~$0.0005)
    IF confirmed → Tier 3: Flag for extraction threshold reduction
  Phase 3 — Pattern extraction:
    IF transcript grew >= ~80KB since last extraction (or 16KB if correction flagged):
      Pattern extraction (Sonnet) →
        Append to .soul/SOUL.md (repo patterns)
        Append to ~/.soul/genome/learned.md (cross-project patterns)
        Log to .soul/staging/recent-extractions.jsonl
        Auto-export skills
        → systemMessage "Extracted N patterns"
  → Resume (with systemMessage if any notifications)
```

### 3. Compaction (manual or auto)
```
/compact or auto-compact → PostCompact hook →
  Phase 1: SOUL.md compaction
    Count bullets per section (before snapshot)
    Read transcript (last 100 entries)
    Read git log (last 10 commits)
    Read .soul/SOUL.md
    Compress via Sonnet →
      Validate: basic + bullet count comparison
      Save diff to .soul/staging/last-compaction-diff.txt
      IF requireApproval: write to staging, await /soul approve-compaction
      ELSE: overwrite .soul/SOUL.md
  Phase 2: Genome compaction (if learned.md > 5000 chars)
    Compress via Sonnet → overwrite learned.md
  Stage changes via git add (no commit)
  Relay notification to next Stop hook via temp file
  → Session resumes with compressed SOUL
```

### 4. Next Session (same or different repo)
```
SessionStart hook →
  Load genome (includes learned.md with cross-project patterns)
  Load SOUL.md (includes repo patterns extracted mid-session)
  → Agent starts with accumulated knowledge
```

### 5. Global Soul Agent (user-triggered, cross-repo)
```
User runs: /soul consolidate  (from any repo)
  OR: cd ~/.soul && claude -p "consolidate genome"
    ↓
  Read ~/.soul/genome/learned.md (all accumulated patterns)
  Read ~/.soul/projects.json (registry of known SOUL-enabled repos)
  For each registered repo:
    Read <repo>/.soul/SOUL.md
  Correlate patterns across repos:
    Pattern in 3+ repos → high-confidence, keep in genome
    Pattern in 1 repo only → demote to repo-specific, remove from genome
  Compress learned.md with full cross-repo context
  Optionally: propagate high-confidence patterns back to repo SOUL.md files
```

`~/.soul/` is treated as its own project with a `CLAUDE.md` that tells the agent its role (genome curator). The user triggers consolidation manually when they want to — no cron job until pattern volume justifies automation.

**Why this can't run in-session hooks:** The session agent only sees one repo's transcript. Cross-repo correlation requires reading SOUL.md files from multiple projects, which the in-session hooks can't do (they only have access to `$CWD`).

---

## Shareable Skills

Skills are imprinted knowledge — a combination of a role description, relevant accumulated knowledge, warnings, and invariants packaged as a standalone Claude Code skill.

### Two forms of skills

**SOUL-managed skills** — declared in `## Skills` of SOUL.md. Regenerated as `.claude/skills/<name>/SKILL.md` every session. These inject live SOUL.md via `!cat` preprocessor directives, so they always see the latest knowledge. These are the "living" form.

**Exported skills** — standalone snapshots at `.soul/exports/<name>/SKILL.md`. All relevant knowledge is baked in (no `!cat` directives). Self-contained, shareable, installable via `npx skills add`. These are the "portable" form.

### Export pipeline

`/soul export <name>` or automatic on compaction:

1. Parse skill definition from `## Skills` in SOUL.md
2. Gather Accumulated Knowledge, Predecessor Warnings, invariants
3. Call `claude -p` (Sonnet) to filter for only knowledge relevant to this skill's role
4. Assemble standalone SKILL.md with YAML frontmatter + baked-in knowledge sections
5. Write PROVENANCE.md with git origin, commit hash, timestamp

**Script:** `export-skill.sh`
**Flags:** `--to <path>` (custom output), `--genome` (write to `~/.soul/genome/skills/<name>/`), `--quiet` (suppress stdout, for automated use)

### Import pipeline

`/soul import <source>` (agent-handled, no script):

1. Read source SKILL.md
2. Extract role → append to `## Skills` in SOUL.md
3. Merge knowledge bullets into `## Accumulated Knowledge` (deduplicate)
4. Merge warnings into `## Predecessor Warnings` (deduplicate)
5. Offer to merge invariants (requires user confirmation — human-authored)

### Genome skills

Skills can live in the genome cascade at `~/.soul/genome/skills/<name>/SKILL.md`.

- `session-start.sh` Phase 6b loads genome skills unless overridden by a same-named repo-level skill
- `/soul export <name> --genome` writes to the genome for cross-project availability
- Follows the cascade rule: repo-level always wins over genome-level

### Skill evolution

1. Knowledge accumulates in SOUL.md via compaction and pattern extraction
2. SOUL-managed skills see latest knowledge via `!cat` injection (automatic)
3. When pattern extraction modifies SOUL.md (~every 20k tokens, or sooner after corrections), `conscience.sh` auto-exports all skills with fresh knowledge
4. Exported snapshots in `.soul/exports/` stay current without user intervention
5. Re-export to genome (`/soul export <name> --genome`) propagates to all projects

### Distribution

Exported skills are valid Claude Code skills. Distribution via:
- **Git:** Commit `.soul/exports/<name>/` and push. Others install with `npx skills add <owner>/<repo> --skill <name>`
- **Genome:** `/soul export <name> --genome` makes it available in all local projects
- **Team sharing (future):** Genome fragments distributed via shared repository

---

## Known Gaps and Future Work

### Gaps in Current Architecture

1. **Global soul agent not yet implemented.** The `~/.soul/` project with `CLAUDE.md` exists only as a design — needs `~/.soul/CLAUDE.md`, `~/.soul/projects.json` (repo registry), and a `/soul consolidate` command. User-triggered, not scheduled.

2. **Correction detection has false negatives.** The three-tier correction detection pipeline catches corrections that use common stop words. Corrections phrased without these markers (e.g., "actually, I meant...") may not trigger early extraction. The regular extraction cadence (~every 20k tokens) still catches them, just not with the 5x-faster response time.

### Resolved

- ~~Single-repo pattern extraction~~ — Solved architecturally by the global soul agent design. In-session extraction promotes patterns to `learned.md`; the global agent correlates across repos on a schedule.
- ~~Extraction and compaction race on SOUL.md~~ — Stop and PostCompact hooks cannot fire concurrently. `/compact` does not trigger Stop. They are sequential, never overlapping.
- ~~No genome compaction~~ — `compact.sh` now compacts `~/.soul/genome/learned.md` when it exceeds 5000 chars, during the same PostCompact event that compacts SOUL.md.
- ~~Token count estimation~~ — With a 1M context window, ±20% on a 20k trigger threshold is irrelevant.
- ~~PreToolUse/PostToolUse unused~~ — Design choice, not a gap. Conscience audit covers invariant checking retrospectively.
- ~~No user-message hook~~ — Three-tier correction detection (stop words → Haiku micro-prompt → extraction flag) provides near-real-time correction capture without a dedicated user-message hook. Corrections trigger extraction within ~4k tokens instead of waiting for the full 20k threshold.
- ~~No feedback loop on extraction~~ — `systemMessage` notifications inform the user when patterns are extracted. `/soul review` provides retrospective review and revert capability. The user can also edit SOUL.md directly; compaction reconciles.
- ~~Auto-commit uses `--no-verify`~~ — Replaced with stage-only behavior (`git add` without `git commit`). No longer violates behavior invariants.
- ~~Compaction validation is two string checks~~ — Now includes section-level bullet count comparison (reject if >50% bullet loss), diff saving, and optional approval gate. Rejected compactions are saved to staging for review.
- ~~No user notification of hook activity~~ — Stop hooks now surface informational messages via `systemMessage`. PostCompact notifications relay through the next Stop hook invocation. All events logged to unified `soul-activity.jsonl`.
