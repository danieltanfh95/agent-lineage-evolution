---
name: soul
description: >
  Persistent agent identity, memory, and behavioral rules across sessions.
  Automatically remembers your role, preferences, and project knowledge
  via rolling compaction. Use /soul to configure.
user-invocable: true
argument-hint: "setup | remember <fact> | update | show | review | approve-compaction"
hooks:
  SessionStart:
    - hooks:
        - type: command
          command: "${CLAUDE_SKILL_DIR}/scripts/session-start.sh"
  Stop:
    - hooks:
        - type: command
          command: "${CLAUDE_SKILL_DIR}/scripts/conscience.sh"
  PostCompact:
    - hooks:
        - type: command
          command: "${CLAUDE_SKILL_DIR}/scripts/compact.sh"
---

# SOUL — Persistent Agent Memory

You have SOUL installed, which gives you persistent memory across sessions.

## Commands

When the user invokes `/soul`, check the argument:

### `/soul setup`
Interactively configure SOUL for this project:

1. If `.soul/` directory doesn't exist, create it along with `.soul/invariants/`
2. Ask the user these questions ONE AT A TIME (wait for each answer):
   - "What's your role? What kind of work do you do with Claude Code?"
   - "What rules should I always follow when working with you?" (give examples: "always confirm before changing files", "use simple language", "show reasoning before conclusions")
   - "Any specific project knowledge I should remember?" (give examples: "our product is called X", "we use Y for docs", "deadline is Z")
3. Write their answers into `.soul/SOUL.md` using this structure:

```markdown
## Identity
I am a [role] assistant working on [project context from their answers].

[Behavioral guidelines from their rules answer]

## Accumulated Knowledge
[Facts from their knowledge answer, as bullet points]

## Predecessor Warnings
No warnings yet.

## Current Understanding
Project freshly configured via /soul setup.

## Skills
No specialized skills defined.
```

4. Write their rules into `.soul/invariants/behavior.md`:
```markdown
# Behavior Invariants
[Each rule as a bullet point prefixed with "- "]
```

5. Create `.soul/config.json` with default settings:
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

6. Configure the status line by reading the current `.claude/settings.json` (or `.claude/settings.local.json`), then merging in:
```json
{
  "statusLine": {
    "type": "command",
    "command": "${CLAUDE_SKILL_DIR}/scripts/statusline.sh"
  }
}
```
Use the Edit tool to add the statusLine field without overwriting existing settings. If a statusLine already exists, replace it.

7. Confirm with a friendly summary. Do NOT mention file paths, hooks, or technical details. Just say what you'll remember and that you'll show a status indicator.

### `/soul remember <fact>`
Append the fact to the `## Accumulated Knowledge` section of `.soul/SOUL.md`.
Confirm: "Got it, I'll remember that."

### `/soul update`
Ask what the user wants to change (role, rules, or knowledge), then update the relevant section of `.soul/SOUL.md` or `.soul/invariants/behavior.md`.

### `/soul show`
Read `.soul/SOUL.md` and display the Identity, Rules, and Knowledge in plain language. Do NOT show raw markdown or file paths.

### `/soul export <skill-name> [--to <path>] [--genome]`
Export a skill from `## Skills` as a standalone, shareable Claude Code skill with relevant knowledge baked in.

1. Run the export script: `"$CLAUDE_PROJECT_DIR"/.soul/hooks/export-skill.sh <skill-name> [options]`
   - If that doesn't exist, try: find `export-skill.sh` in the skill's scripts directory
2. If no skill name given, read the `## Skills` section and list available skills for the user to pick
3. Report what was created:
   - Default: "Exported to .soul/exports/<name>/ — push to GitHub and others can install with `npx skills add <owner>/<repo> --skill <name>`"
   - With `--genome`: "Saved to genome. Skill is now available in all your projects."
   - With `--to <path>`: "Exported to <path>/"

### `/soul import <source>`
Import a shared skill into your project. `<source>` can be:
- A local directory path containing a SKILL.md
- `genome:<skill-name>` to import from `~/.soul/genome/skills/<name>/`

Steps:
1. Read the source `SKILL.md`
2. Extract the role description from `# Role` section
3. Check if `mode: fork` should be set (look for `context: fork` in YAML frontmatter)
4. Append the skill to `## Skills` section of `.soul/SOUL.md` as a `### <name>` block
5. Read `# Relevant Knowledge` section — merge new bullets into `## Accumulated Knowledge` in SOUL.md, skipping duplicates
6. Read `# Warnings` section — merge new bullets into `## Predecessor Warnings` in SOUL.md, skipping duplicates
7. If `# Invariants` section exists, show the invariants to the user and ask if they want to add them to `.soul/invariants/` (invariants are human-authored — never auto-merge)
8. Confirm: "Imported <name> skill with N knowledge points and M warnings. Active next session."

### `/soul review`
Review what SOUL has recently learned and optionally undo entries.

1. Read `.soul/staging/recent-extractions.jsonl`
2. If the file doesn't exist or is empty: "Nothing to review — I haven't learned anything new recently."
3. For each extraction entry, display in plain language:
   - When it was learned and what triggered it
   - Each pattern: what I learned, why, and a quote from the conversation
   - Whether it applies to this project only or all your projects
4. Ask the user if they want to undo any entries. For each undone entry:
   - Remove the corresponding bullet from `## Accumulated Knowledge` or `## Predecessor Warnings` in `.soul/SOUL.md`
   - Remove the corresponding line from `~/.soul/genome/learned.md` if applicable
   - Log a `user_revert` event to `.soul/log/soul-activity.jsonl`:
     ```json
     {"timestamp": "...", "session": "...", "event": "user_revert", "pattern_summary": "...", "section": "...", "reverted_bullet": "..."}
     ```
5. After processing, confirm: "Done — removed N entries from my memory."
6. Optionally: "Want me to clear the review history too?"

### `/soul approve-compaction`
Review a pending knowledge compression before it's applied.

1. Check if `.soul/staging/pending-compaction.md` exists
2. If not: "No pending compression to review."
3. Show what changed: display the diff in a readable format with before/after sizes
4. Ask the user to approve or reject
5. If approved:
   - Copy `.soul/staging/pending-compaction.md` to `.soul/SOUL.md`
   - Log `user_approve_compaction` event to `.soul/log/soul-activity.jsonl`
   - Delete staging files (pending-compaction.md, last-compaction-diff.txt)
   - Confirm: "Applied — my knowledge is now up to date."
6. If rejected:
   - Log `user_reject_compaction` event to `.soul/log/soul-activity.jsonl`
   - Delete staging files
   - Confirm: "Discarded — keeping my current knowledge as-is."

### `/soul skills`
List all available skills and their source.

1. Read `## Skills` section of `.soul/SOUL.md` for repo-level skills
2. Check `~/.soul/genome/skills/` for genome-level skills not overridden by repo
3. Check `.soul/exports/` for exported snapshots
4. Display each skill with: name, description, source (repo/genome), and whether an export exists

## Behavior When SOUL Is Active

When `.soul/SOUL.md` exists and has been loaded (you'll see it in your context at session start):

1. Follow ALL rules in the Identity section and invariants without exception
2. Reference Accumulated Knowledge when relevant to the current task
3. Heed Predecessor Warnings to avoid known pitfalls
4. Communicate in language appropriate to the user's stated role
5. When you learn something important during a session, it will be automatically saved during compaction — you don't need to do anything special

## When SOUL Is Not Yet Configured

If `.soul/SOUL.md` doesn't exist, gently suggest: "I notice you have SOUL installed but haven't set it up yet. Type /soul setup to configure persistent memory — it takes about 30 seconds."
