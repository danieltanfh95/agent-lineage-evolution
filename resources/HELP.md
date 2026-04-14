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
  observations     Inspect or prune observation files  (status | prune --older-than Nd)
  contradictions   Inspect or clear contradictions  (list | clear-resolved [--older-than Nd])
  archive          Inspect or prune archive snapshots  (list | prune --keep-last N)
  queue            Inspect the async judge queue
  worker           Manage the drain worker  (drain | logs [--follow])
  identity-diff    Diff two archived identity snapshots  (--last | --list)
  config           Validate config.edn and hook paths
  statusline       Emit the Claude Code status line fragment
  --install-skill  Write this document as .claude/skills/succession/SKILL.md

## Statusline

The Claude Code statusline fragment `succession: ✓N ✗N · judging N` shows:
- ✓N — cards reinforced in recent sessions
- ✗N — contradictions detected
- judging N — jobs queued for async LLM evaluation

## Install

```
succession install
```

Hooks fire automatically once installed — no manual worker start needed.
Run `succession queue status` to inspect the async judge queue.
Run `succession show` to see accumulated identity cards.
