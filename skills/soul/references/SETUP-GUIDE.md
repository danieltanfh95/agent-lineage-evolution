# SOUL Setup Guide

SOUL gives Claude Code persistent memory across sessions — your preferences, project knowledge, and behavioral rules survive between conversations.

## Quick Start

1. Type `/soul setup` in Claude Code
2. Answer 3 questions about your role, rules, and project knowledge
3. Done! Claude will remember across all future sessions in this project.

## Commands

- `/soul setup` — Configure your preferences (first time or reconfigure)
- `/soul remember <fact>` — Save a specific fact (e.g., `/soul remember our deploy target is AWS ECS`)
- `/soul update` — Change a specific preference
- `/soul show` — See your current configuration

## What Gets Remembered

- **Your role** — So Claude communicates appropriately
- **Your rules** — Behavioral guidelines Claude always follows
- **Project knowledge** — Facts about your project, team, tools
- **Lessons learned** — Mistakes and pitfalls discovered during sessions (saved automatically)

## How It Works (simplified)

SOUL stores your preferences in a `.soul/` directory in your project. At the start of each session, these preferences are loaded. When context gets compacted (in long sessions), your knowledge is automatically updated. A conscience system checks that Claude follows your rules.

You never need to edit any files — just use the `/soul` commands.
