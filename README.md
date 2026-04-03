# Succession — Behavioral Pattern Extraction for AI Coding Agents

[![DOI](https://zenodo.org/badge/DOI/PLACEHOLDER.svg)](https://doi.org/PLACEHOLDER)

Succession detects behavioral corrections from your conversations with AI coding agents and turns them into persistent, enforceable rules. It solves the problem of AI agents forgetting your preferences between sessions and within long sessions (instruction drift at ~150k tokens).

Successor to [SOUL](docs/archive/soul-framework-whitepaper.md) and [ALE](docs/ale-blog-post-2025.md), focusing on behavioral pattern extraction over full agent governance.

## The Problem

You tell your AI agent "don't use subagents" or "always read before editing." It follows for a while, then forgets — either when the session ends or after enough context accumulates. You correct the same behavior over and over.

## How Succession Works

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
./scripts/succession-extract-cli.sh --last           # Most recent session
./scripts/succession-extract-cli.sh --interactive     # Explore transcript interactively
./scripts/succession-skill-extract.sh --last --apply  # Extract replayable skill bundle
```

## Quick Start

```bash
./scripts/succession-init.sh
```

This creates `~/.succession/` (global rules + hooks) and `.succession/` (project rules), and registers hooks in `~/.claude/settings.json`.

If [babashka](https://github.com/babashka/babashka) is installed, the init script will register the bb-based hooks (Clojure, better data structures, ~10ms startup). Otherwise it falls back to bash+jq hooks. Install bb with:

```bash
bash < <(curl -s https://raw.githubusercontent.com/babashka/babashka/master/install)
```

Then use Claude Code normally. Succession runs silently in the background — when you correct the agent, rules are extracted automatically.

### Commands (via /succession skill)

| Command | What it does |
|---------|-------------|
| `/succession show` | Show all active rules after cascade resolution |
| `/succession review` | Review recently extracted rules, enable/disable/delete |
| `/succession add <text>` | Manually add a rule |
| `/succession extract` | Retrospective extraction from past transcripts |
| `/succession skill extract` | Extract replayable skill from a transcript |
| `/succession resolve` | Manually re-run cascade resolution |

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
~/.succession/rules/           # Global rules (all projects)
.succession/rules/             # Project rules (override global with same id)
```

Resolution: project rules with the same `id` override global rules. Rules with `overrides: [id]` explicitly cancel referenced rules. Disabled rules (`enabled: false`) are filtered out.

### Hook Architecture

| Hook | Event | Type | Purpose |
|------|-------|------|---------|
| `succession-session-start.sh` | SessionStart | command | Cascade resolve + inject advisory rules |
| `succession-pre-tool-use.sh` | PreToolUse | command | Mechanical enforcement (free, deterministic) |
| (inline prompt) | PreToolUse | prompt | Semantic enforcement (Sonnet, ~$0.005) |
| `succession-stop.sh` | Stop | command | Correction detection + extraction + re-injection |
| (inline prompt) | Stop | prompt | Response audit against advisory rules |

### Skill Extraction

Extract replayable skill bundles from transcripts — a SKILL.md containing trigger conditions, workflow steps, domain knowledge, and task-specific rules:

```
~/.succession/skills/<name>/SKILL.md    # Global skills
.succession/skills/<name>/SKILL.md      # Project skills
```

## Directory Structure

```
scripts/                              # Bash hook scripts and CLI tools
  lib.sh                              # Shared utilities
  succession-resolve.sh               # Cascade resolution → compiled artifacts
  succession-pre-tool-use.sh          # Mechanical PreToolUse enforcement
  succession-stop.sh                  # Stop hook (detection + extraction + re-injection)
  succession-session-start.sh         # SessionStart hook
  succession-init.sh                  # One-time setup (detects bb, registers hooks)
  succession-extract-cli.sh           # Retrospective rule extraction
  succession-skill-extract.sh         # Retrospective skill extraction
  SKILL.md                            # /succession commands
bb/                                   # Babashka (Clojure) implementation
  bb.edn                              # Project config
  src/succession/
    yaml.clj                          # Rule file I/O (YAML frontmatter ↔ Clojure maps)
    resolve.clj                       # Cascade resolution
    effectiveness.clj                 # Meta-cognition tracking + analysis
    core.clj                          # CLI entry point
    hooks/
      pre_tool_use.clj                # Mechanical enforcement
      session_start.clj               # Resolve + inject
      stop.clj                        # Correction detection + extraction + re-injection
  test/succession/                    # Pure function tests (clojure.test)
tests/
  test_succession.sh                  # Bash hook regression tests (no API calls)
docs/                                 # Architecture docs and whitepaper
  archive/                            # Previous SOUL framework docs
experiments/                          # Empirical validation
```

## Documentation

- **[Architecture (coming soon)](docs/succession-architecture.md)** — Technical description of the three-tier enforcement model
- **[ALE Blog Post](docs/ale-blog-post-2025.md)** — The 2025 predecessor: Agent Lineage Evolution
- **[SOUL Whitepaper](docs/archive/soul-framework-whitepaper.md)** — Previous iteration: full agent governance
- **[Experiments](experiments/README.md)** — Empirical validation protocols

## Prior Work

Succession is the third iteration of this research:

1. **ALE (2025)** — Agent Lineage Evolution: episodic succession, agents pass memory packages to successors
2. **SOUL (2026)** — Structured Oversight of Unified Lineage: continuous governance with conscience audit loops, genome cascade, rolling compaction
3. **Succession (2026)** — Focused on behavioral pattern extraction and mechanical enforcement. Drops identity/knowledge concerns. Adds CSS-like rule cascading, retrospective transcript analysis, and skill extraction.

## Citation

```bibtex
@software{tan_succession_2026,
  author = {Tan, Daniel},
  title = {Succession: Behavioral Pattern Extraction for AI Coding Agents},
  year = {2026},
  url = {https://github.com/danieltanfh95/agent-lineage-evolution},
  version = {2.0.0}
}
```

## License

[MIT](LICENSE)
