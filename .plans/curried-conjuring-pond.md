# Plan: Context Depth + Correction Persistence — Multi-Model Sweep

## Context

The context-depth experiment (01) showed Sonnet follows instructions well at 150k (A=100%, B=100%, C=0%). Now we want to:

1. **Run 01-context-depth across open models** (mimo, deepseek, glm5) via sheath-openrouter — do they maintain compliance at 150k?
2. **Build and run 02-correction-persistence** — do user corrections survive when buried 100k+ tokens back? Test across Claude models AND open models.

Sheath is now patched and working with OpenRouter.

## Part 1: Run 01-context-depth on Open Models

Already implemented. Just run:
```bash
python context_depth.py --model mimo --cli sheath-openrouter --all-conditions --reps 3
python context_depth.py --model deepseek --cli sheath-openrouter --all-conditions --reps 3
python context_depth.py --model glm5 --cli sheath-openrouter --all-conditions --reps 3
```

No code changes needed — harness already supports `--cli` flag.

## Part 2: Correction Persistence Experiment

### What We're Testing

Can the model **detect** a user correction and **retain** it across continued work at high context depth?

### Session Structure (7 turns)

| Turn | Type | Purpose |
|------|------|---------|
| 0 | Padded filler | Establish 150k context depth |
| 1 | Task | Coding task — model produces output naturally |
| 2 | Correction | User corrects a specific behavior ("Use single quotes!") |
| 3 | Filler task | More work — pushes correction deeper into context |
| 4 | Filler task | Even more work — correction now ~50k tokens back |
| 5 | Filler task | Correction now ~100k tokens back |
| 6 | Probe | Same type of task as T1 — does the correction from T2 still apply? |

### Conditions (simplified — conversation history only)

| Condition | CLAUDE.md | Hooks | What it tests |
|-----------|-----------|-------|---------------|
| **A** | Rules | None | Does model remember correction from T2 at T6? |
| **C** | No rules | None | Baseline — correction is just another message |

No auto-memory, no Succession Stop hook (user chose to keep it simple).

### Correction Types

1. **Quote style**: "Use single quotes in Python!" (model defaults to double)
2. **Response format**: "Always start with ## Plan!" (advisory, easy to forget)
3. **Tool preference**: "Don't use Bash for file edits, use the Edit tool!" (model sometimes uses sed)

### Scoring

For probe turn (T6):
- **Correction retained**: Does model follow the T2 correction? (reuse existing scorers)
- **Task completed**: Did model attempt the task?

### Filler tasks T3-T5

Real coding tasks (not padding) — generates natural context growth:
1. Real tasks add tool calls which grow context naturally
2. Model's attention is on coding, not remembering corrections
3. Simulates real usage where user corrects early and expects it to stick

Pad T0 to 150k, then T3-T5 are real coding tasks adding ~30-50k more tokens.

## Files to Create/Modify

| File | Changes |
|------|---------|
| `02-correction-persistence/correction_persistence.py` | New experiment script, 7-turn sessions |
| `02-correction-persistence/probes.json` | Correction + probe pairs + filler tasks |
| `common/scorer.py` | No changes needed — existing scorers cover all correction types |

Reuse: `common/harness.py`, `common/config.py`, `common/padding.py` as-is.

## Implementation Steps

1. Create `02-correction-persistence/probes.json` with correction/probe pairs and filler tasks
2. Create `02-correction-persistence/correction_persistence.py` — 7-turn session runner
3. Dry-run to verify structure
4. Run canary: Sonnet × all conditions × 1 rep
5. Run open model sweep: mimo, deepseek, glm5 via sheath-openrouter
6. Run 01-context-depth on open models in parallel

## Verification

1. `--dry-run` to verify session structure and turn sequencing
2. Canary with Sonnet: conditions A+C × 1 rep
3. Open model canary: mimo × conditions A+C × 1 rep
4. Full sweep across all models if canary looks good
