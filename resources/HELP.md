# succession

Succession maintains a persistent identity for you across Claude Code sessions —
tracking your observed behaviors, preferences, and principles as "cards" that survive
context resets.

## When to consult

Run `succession consult "<situation>"` when you are:
- Uncertain whether to proceed with an action
- Sensing a contradiction with prior behavior
- About to do something you vaguely remember being warned against

Do not consult when the action is clearly routine, or you consulted in the last few
turns about the same thing.

## Commands

  install          Install hooks and config into a Claude Code project
  consult          Query your identity cards against a situation
  show             Display current identity cards
  compact          Compact staging deltas into the card store
  status           Dashboard overview of the whole .succession/ folder
  staging          Inspect or prune the staging area
  observations     Inspect or prune observation files  (status | prune --older-than N(s|m|h|d))
  contradictions   Inspect or clear contradictions  (list | clear-resolved [--older-than N(s|m|h|d)])
  archive          Inspect or prune archive snapshots  (list | prune --keep-last N)
  queue            Inspect the async judge queue
  worker           Manage the drain worker  (drain | logs [--follow])
  identity-diff    Diff two archived identity snapshots  (list | last | <ts1> [ts2])
  replay           Replay a session transcript through the identity cycle in a sandbox
  import           One-shot migration from old `.succession/rules/*.md` YAML files
  bench            Run judge regression / cost / latency benchmark
  config           Validate, display, or write a starter config.edn  (validate | show | init)
  statusline       Emit the Claude Code status line fragment
  --install-skill  Write this document as .claude/skills/succession/SKILL.md

## Statusline

The Claude Code statusline fragment `succession: ✓N ✗N · judging N` shows:
- ✓N — cards reinforced in recent sessions
- ✗N — contradictions detected
- judging N — jobs queued for async LLM evaluation

## Install

```
succession install [--global | --local] [--starter-pack]
```

- `--global` — write hooks to `~/.claude/settings.json` once. Per-project store
  (`.succession/`) is created automatically on first SessionStart in each project —
  no per-project install step needed.
- `--local` — (default) write hooks to `.claude/settings.local.json` and create the
  per-project store immediately in the current directory.
- `--starter-pack` — seed the project store with 4 curated starter cards (verify-via-repl, data-first-design, infinite-context, judge-conscience-framing).

  Starter cards are also seeded automatically on first SessionStart when `:auto-install/starter-pack true` (the default) is set in `config.edn`.

Hooks fire automatically once installed — no manual worker start needed.
Run `succession queue status` to inspect the async judge queue.
Run `succession show` to see accumulated identity cards.
