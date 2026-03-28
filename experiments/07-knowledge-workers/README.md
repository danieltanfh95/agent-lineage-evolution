# Experiment 07: SOUL for Knowledge Workers

## Hypothesis

SOUL-configured knowledge workers (PMs, analysts) receive more contextually appropriate, rule-compliant, and knowledge-aware responses than those using CLAUDE.md or no persistent memory, particularly after preference updates.

## Design

Two personas (PM, analyst) × 5 sessions each × 3 conditions.

### Scenarios

| Scenario | Persona | Key tests |
|----------|---------|-----------|
| `pm-specs` | Product Manager | Remembers product (Athena), follows spec format, plain language |
| `analyst-data` | Data Analyst | Uses correct tables (Snowflake), shows methodology, knows fiscal calendar |

### Session Flow

| Session | What happens |
|---------|-------------|
| 1 | `/soul setup` (simulated via scripted answers) |
| 2 | First task — role awareness, rule compliance, knowledge use |
| 3 | Second task (new session) — still remembering? |
| 4 | Preference update: "I prefer tables over bullet points" |
| 5 | Third task — uses UPDATED preference? |

### Conditions

| Condition | Memory source |
|-----------|--------------|
| `soul-skill` | SOUL.md + invariants (simulates `/soul setup` output) |
| `claude-md` | Same info as flat CLAUDE.md system prompt |
| `no-memory` | No persistent context |

### Scoring

Per session (2, 3, 5): keyword checks for role awareness, rule compliance, knowledge use, jargon avoidance, and preference update.

## Running

```bash
python runner.py --scenario scenarios/pm-specs --condition soul-skill
python runner.py --scenario scenarios/analyst-data --condition no-memory
```

## Files

- `runner.py` — Runs scenarios with claude -p, scores responses
- `scenarios/pm-specs/` — PM persona (setup, tasks, ground truth)
- `scenarios/analyst-data/` — Analyst persona (setup, tasks, ground truth)
