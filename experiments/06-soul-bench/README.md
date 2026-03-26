# Experiment 06: SOUL-Bench

## Hypothesis

SOUL's rolling compaction preserves code project knowledge (architectural decisions, predecessor warnings, technical facts) across sessions while pruning stale information and resolving contradictions. Additionally, SOUL's proactive compaction keeps agents in a "high quality zone" (< 150k tokens) where instruction following remains reliable.

## Design

### Layer 1: Knowledge Retention

After 10 sessions of simulated coding work on an Express.js API, score the agent's ability to answer 20 questions about the project using only the compacted SOUL.md as context.

**Categories:**
- **Retention** (8 questions): Facts that should survive compaction
- **Contradiction** (4 questions): Facts that changed — newer info should win
- **Staleness** (2 questions): Facts that are no longer true — should be pruned
- **Abstention** (3 questions): Facts never mentioned — should say "I don't know"
- **Warning** (3 questions): Predecessor warnings / lessons learned — should survive

**Conditions:**
- `soul-sonnet` — Sonnet 4.6 compaction (recommended default)
- `soul-haiku` — Haiku compaction (known-broken baseline)
- `append-only` — Concatenate all sessions without compaction
- `no-memory` — No context at all

### Layer 2: Context Quality / Instruction Drift (future)

Test whether SOUL maintains behavioral compliance (plan-before-action, docs-before-code) at high context depths where vanilla agents drift.

## Running

```bash
# Run one condition
python runner.py --scenario scenarios/express-api --condition soul-sonnet

# Score results
python scorer.py results/express-api/soul-sonnet/predictions.json scenarios/express-api/ground-truth.json --memory results/express-api/soul-sonnet/memory.md
```

## Scenario: express-api

10 sessions building a Node.js/Express REST API:

| Session | Work | Knowledge Tension |
|---------|------|-------------------|
| 1 | Express + SQLite + CRUD | Baseline |
| 2 | JWT auth | New pattern |
| 3 | Fix auth bug (expired tokens accepted) | Predecessor warning |
| 4 | Refactor: raw SQL → Knex | Stale: raw SQL no longer used |
| 5 | Add Redis caching | New dependency |
| 6 | Swap Redis → Memcached | Contradiction: Redis replaced |
| 7 | Fix N+1 query | Technical knowledge |
| 8 | Add Jest tests (80% coverage) | New tooling |
| 9 | Remove DELETE endpoint (GDPR) | Staleness: endpoint gone |
| 10 | Cleanup + docs | Final state |

## Results (Layer 1, express-api)

| Category | SOUL Sonnet | SOUL Haiku | Append-Only | No Memory |
|----------|-----------|-----------|------------|-----------|
| Retention (8) | 8/8 (100%) | 8/8 (100%) | 8/8 (100%) | 0/8 (0%) |
| Contradiction (4) | 4/4 (100%) | 4/4 (100%) | 4/4 (100%) | 1/4 (25%) |
| Staleness (2) | 2/2 (100%) | 2/2 (100%) | 2/2 (100%) | 2/2 (100%) |
| Abstention (3) | 3/3 (100%) | 3/3 (100%) | 3/3 (100%) | 3/3 (100%) |
| Warning (3) | 3/3 (100%) | 2/3 (67%) | 3/3 (100%) | 0/3 (0%) |
| **Overall** | **20/20 (100%)** | 19/20 (95%) | 20/20 (100%) | 6/20 (30%) |

**Memory sizes:** SOUL Sonnet: 5,151 chars | SOUL Haiku: 5,831 chars | Append-Only: 9,745 chars

### Key Findings

1. **SOUL with Sonnet 4.6 achieves perfect recall** on code project knowledge — all facts retained, contradictions resolved, stale info pruned, predecessor warnings preserved.
2. **Haiku works on short sessions** — unlike LongMemEval (where Haiku's memory ballooned to 34k chars on 10k-char sessions), Haiku handles ~500-char sessions fine. The compaction failure is input-size dependent.
3. **Append-only also scores 20/20** — because 10 short sessions only produce ~10k chars of memory, which Opus can easily read. SOUL's compression advantage (1.9x) is modest at this scale.
4. **The eval needs more sessions / longer sessions** to reach the point where append-only breaks down and SOUL's compression becomes the differentiator. This is what Layer 2 (instruction drift at >150k tokens) will test.

## Files

- `runner.py` — Runs compaction + QA pipeline
- `scorer.py` — Scores predictions against ground truth
- `scenarios/express-api/` — Scenario files (codebase, sessions, ground truth, invariants)
