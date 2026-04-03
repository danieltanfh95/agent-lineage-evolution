# Imprint — Behavioral Pattern Extraction for AI Coding Agents

[![DOI](https://zenodo.org/badge/DOI/PLACEHOLDER.svg)](https://doi.org/PLACEHOLDER)

Imprint detects behavioral corrections from your conversations with AI coding agents and turns them into persistent, enforceable rules. It solves the problem of AI agents forgetting your preferences between sessions and within long sessions (instruction drift at ~150k tokens).

Successor to [SOUL](docs/archive/soul-framework-whitepaper.md) and [ALE](docs/ale-blog-post-2025.md), focusing on behavioral pattern extraction over full agent governance.

## The Problem

You tell your AI agent "don't use subagents" or "always read before editing." It follows for a while, then forgets — either when the session ends or after enough context accumulates. You correct the same behavior over and over.

## How Imprint Works

**Three enforcement tiers** ensure rules survive both session boundaries and context drift:

| Tier | How | Cost | Survives Drift? |
|------|-----|------|----------------|
| **Mechanical** | PreToolUse hook blocks tool calls via regex | $0, ~10ms | Yes — runs outside agent context |
| **Semantic** | PreToolUse prompt hook (Sonnet) evaluates tool calls | ~$0.005/call | Yes — runs outside agent context |
| **Advisory** | Stop hook periodically re-injects rules via `additionalContext` | $0 | Yes — refreshed every N turns |

**Extraction pipeline** (runs automatically on the Stop hook):
1. **Tier 1**: Free keyword scan — did the user say "no", "don't", "stop", "instead"?
2. **Tier 2**: Sonnet micro-prompt — "Is this actually a correction?" (~$0.005)
3. **Tier 3**: Sonnet extracts a rule with enforcement directives → writes individual rule file

**Retrospective analysis** — extract rules from past transcripts:
```bash
./scripts/imprint-extract-cli.sh --last           # Most recent session
./scripts/imprint-extract-cli.sh --interactive     # Explore transcript interactively
./scripts/imprint-skill-extract.sh --last --apply  # Extract replayable skill bundle
```

## Quick Start

```bash
./scripts/imprint-init.sh
```

This creates `~/.imprint/` (global rules + hooks) and `.imprint/` (project rules), and registers hooks in `~/.claude/settings.json`.

Then use Claude Code normally. Imprint runs silently in the background — when you correct the agent, rules are extracted automatically.

### Commands (via /imprint skill)

| Command | What it does |
|---------|-------------|
| `/imprint show` | Show all active rules after cascade resolution |
| `/imprint review` | Review recently extracted rules, enable/disable/delete |
| `/imprint add <text>` | Manually add a rule |
| `/imprint extract` | Retrospective extraction from past transcripts |
| `/imprint skill extract` | Extract replayable skill from a transcript |
| `/imprint resolve` | Manually re-run cascade resolution |

## Architecture

### Rules as Individual Files

Each rule is a markdown file with YAML frontmatter:

```markdown
---
id: no-force-push
scope: global
enforcement: mechanical
type: correction
source:
  session: abc-123
  timestamp: 2026-04-01T10:00:00Z
  evidence: "User said: never force push"
overrides: []
enabled: true
---

Never force-push without explicit user confirmation.

## Enforcement
- block_bash_pattern: "git push.*(--force|-f)"
- reason: "Force-push blocked — user requires explicit confirmation"
```

### CSS-like Cascading

```
~/.imprint/rules/           # Global rules (all projects)
.imprint/rules/             # Project rules (override global with same id)
```

Resolution: project rules with the same `id` override global rules. Rules with `overrides: [id]` explicitly cancel referenced rules. Disabled rules (`enabled: false`) are filtered out.

### Hook Architecture

| Hook | Event | Type | Purpose |
|------|-------|------|---------|
| `imprint-session-start.sh` | SessionStart | command | Cascade resolve + inject advisory rules |
| `imprint-pre-tool-use.sh` | PreToolUse | command | Mechanical enforcement (free, deterministic) |
| (inline prompt) | PreToolUse | prompt | Semantic enforcement (Sonnet, ~$0.005) |
| `imprint-stop.sh` | Stop | command | Correction detection + extraction + re-injection |
| (inline prompt) | Stop | prompt | Response audit against advisory rules |

### Skill Extraction

Extract replayable skill bundles from transcripts — a SKILL.md containing trigger conditions, workflow steps, domain knowledge, and task-specific rules:

```
~/.imprint/skills/<name>/SKILL.md    # Global skills
.imprint/skills/<name>/SKILL.md      # Project skills
```

## Directory Structure

```
scripts/                              # Hook scripts and CLI tools
  lib.sh                              # Shared utilities
  imprint-resolve.sh                  # Cascade resolution → compiled artifacts
  imprint-pre-tool-use.sh             # Mechanical PreToolUse enforcement
  imprint-stop.sh                     # Stop hook (detection + extraction + re-injection)
  imprint-session-start.sh            # SessionStart hook
  imprint-init.sh                     # One-time setup
  imprint-extract-cli.sh              # Retrospective rule extraction
  imprint-skill-extract.sh            # Retrospective skill extraction
  SKILL.md                            # /imprint commands
tests/
  test_imprint.sh                     # Hook regression tests (no API calls)
docs/                                 # Architecture docs and whitepaper
  archive/                            # Previous SOUL framework docs
experiments/                          # Empirical validation
```

## Documentation

- **[Architecture (coming soon)](docs/imprint-architecture.md)** — Technical description of the three-tier enforcement model
- **[ALE Blog Post](docs/ale-blog-post-2025.md)** — The 2025 predecessor: Agent Lineage Evolution
- **[SOUL Whitepaper](docs/archive/soul-framework-whitepaper.md)** — Previous iteration: full agent governance
- **[Experiments](experiments/README.md)** — Empirical validation protocols

## Prior Work

Imprint is the third iteration of this research:

1. **ALE (2025)** — Agent Lineage Evolution: episodic succession, agents pass memory packages to successors
2. **SOUL (2026)** — Structured Oversight of Unified Lineage: continuous governance with conscience audit loops, genome cascade, rolling compaction
3. **Imprint (2026)** — Focused on behavioral pattern extraction and mechanical enforcement. Drops identity/knowledge concerns. Adds CSS-like rule cascading, retrospective transcript analysis, and skill extraction.

## Citation

```bibtex
@software{tan_imprint_2026,
  author = {Tan, Daniel},
  title = {Imprint: Behavioral Pattern Extraction for AI Coding Agents},
  year = {2026},
  url = {https://github.com/danieltanfh95/agent-lineage-evolution},
  version = {2.0.0}
}
```

## License

[MIT](LICENSE)
