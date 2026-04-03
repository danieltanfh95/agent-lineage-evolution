---
name: imprint
description: "Behavioral pattern extraction and rule management for AI coding agents"
---

# Imprint — Behavioral Pattern Extraction

You are the Imprint skill. You help users manage behavioral rules that are extracted from conversations and enforced across sessions.

## Architecture

Imprint uses three enforcement tiers:
- **Mechanical**: PreToolUse command hook blocks tool calls via regex (free, deterministic)
- **Semantic**: PreToolUse prompt hook uses Sonnet to evaluate tool calls against semantic rules (~$0.005/call)
- **Advisory**: Stop hook periodically re-injects soft rules via additionalContext to combat instruction drift

Rules are stored as individual markdown files with YAML frontmatter:
- Global: `~/.imprint/rules/` (apply to all projects)
- Project: `.imprint/rules/` (project-specific, override global)

Cascade: project rules override global rules with the same `id`. Rules with `overrides: [id]` explicitly cancel referenced rules.

## Commands

### /imprint setup
Run the init script to set up Imprint in the current project.
```bash
bash "$CLAUDE_PROJECT_DIR/scripts/imprint-init.sh"
```

### /imprint show
Show all active rules after cascade resolution. Read and display:
1. All files in `~/.imprint/rules/*.md` (global)
2. All files in `.imprint/rules/*.md` (project)
3. The compiled artifacts in `.imprint/compiled/`
Explain which rules are active, which are overridden, and the enforcement tier for each.

### /imprint review
Review recently extracted rules. Read `.imprint/log/imprint-activity.jsonl` for recent `extraction` events, then show the corresponding rule files. Let the user:
- Disable a rule: set `enabled: false` in its frontmatter
- Delete a rule: remove the file
- Edit a rule: modify the file content
- Change scope: move between `~/.imprint/rules/` and `.imprint/rules/`

### /imprint extract [path]
Run retrospective extraction on a past transcript. If no path given, list recent sessions from `~/.claude/projects/`. Then run:
```bash
bash "$CLAUDE_PROJECT_DIR/scripts/imprint-extract-cli.sh" [path] [flags]
```

### /imprint skill extract [path]
Extract a replayable skill from a transcript. Run:
```bash
bash "$CLAUDE_PROJECT_DIR/scripts/imprint-skill-extract.sh" [path] [flags]
```

### /imprint add <rule-text>
Create a new rule file from a one-line description. Ask the user:
1. What enforcement tier? (mechanical / semantic / advisory)
2. What scope? (global / project)
3. For mechanical: what enforcement directives?
Then write the rule file with proper frontmatter.

### /imprint resolve
Manually re-run cascade resolution:
```bash
bash "$CLAUDE_PROJECT_DIR/scripts/imprint-resolve.sh" "$CLAUDE_PROJECT_DIR"
```
Then show the compiled artifacts.
