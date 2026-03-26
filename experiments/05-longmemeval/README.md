# Experiment 05: SOUL vs. LongMemEval

## Hypothesis

SOUL's lossy compaction can retain enough information from ~40 multi-session conversations (~115k tokens) compressed into ~1,100 tokens to answer personal-memory questions competitively — particularly for **Knowledge Updates**, where compaction's "newer wins" resolution strategy aligns with the benchmark's expectations.

## Background

[LongMemEval](https://github.com/xiaowu0162/LongMemEval) (ICLR 2025) benchmarks chat assistants' long-term memory across multi-session conversations. It contains 500 manually curated questions across 5 categories, with ~40 sessions per instance (~115k tokens per instance in the `longmemeval_s` variant).

**The core tension:** LongMemEval assumes granular recall from extensive history. SOUL compresses everything into a compact memory document via lossy compaction. This is fundamentally different from the RAG or full-context approaches the benchmark was designed for — which makes it an interesting test of the compaction paradigm's limits and strengths.

## Design

**Type:** Between-conditions benchmark comparison

### Conditions

| Condition | Compaction Model | Reader Model | Frequency | Description |
|-----------|-----------------|-------------|-----------|-------------|
| `soul-haiku` | Haiku 4.5 | Opus 4.6 | Every session | Default SOUL behavior |
| `soul-sonnet` | Sonnet 4.5 | Opus 4.6 | Every session | Higher quality compaction |
| `soul-opus` | Opus 4.6 | Opus 4.6 | Every session | Best possible compaction |
| `soul-haiku-5` | Haiku 4.5 | Opus 4.6 | Every 5 sessions | Batch compaction |
| `no-memory` | N/A | Opus 4.6 | N/A | No context baseline |
| `full-context` | N/A | Opus 4.6 | N/A | Full history (oracle upper bound) |

Reader model is fixed to Opus across all conditions to isolate compaction quality from reader capability.

### LongMemEval Question Categories

| Category | Abbrev | Tests |
|----------|--------|-------|
| Information Extraction | IE | Recall of specific details from sessions |
| Multi-Session Reasoning | MR | Synthesis across multiple sessions |
| Knowledge Updates | KU | Tracking changes in user info over time |
| Temporal Reasoning | TR | Time-aware questions about events |
| Abstention | ABS | Correctly saying "I don't know" |

### Predictions

| Category | Prediction | Rationale |
|----------|-----------|-----------|
| IE | Poor (30-40%) | Specific details likely lost in compaction |
| MR | Moderate (40-55%) | Compaction merges patterns — some aggregation preserved |
| KU | Good (55-70%) | Compaction explicitly resolves contradictions |
| TR | Poor (25-35%) | Timestamps largely lost during compression |
| ABS | Moderate (40-50%) | Compacted memory may correctly lack absent info |

**Overall prediction:** 35-50% with Haiku compaction, 45-60% with Opus compaction.
Comparison: Full-context GPT-4o gets ~58% on LongMemEval.

## Prerequisites

- Python 3.10+
- `anthropic` Python SDK: `pip install anthropic`
- `huggingface_hub` (for dataset download): `pip install huggingface_hub`
- `ANTHROPIC_API_KEY` environment variable set
- `OPENAI_API_KEY` environment variable set (for GPT-4o evaluation, as LongMemEval uses GPT-4o)
- Git (for cloning LongMemEval eval scripts)

## Running

### Quick test (5 instances)

```bash
./run_benchmark.sh --limit 5
```

### Single condition

```bash
python adapter.py --config configs/haiku.json --data data/longmemeval_s_cleaned.json --limit 5
```

### Full benchmark

```bash
./run_benchmark.sh
```

This will:
1. Download the LongMemEval dataset from HuggingFace (if not present)
2. Clone the LongMemEval repo (for evaluation scripts)
3. Run the adapter for default conditions (no-memory, haiku, sonnet, haiku-every5)
4. Run LongMemEval evaluation (requires OpenAI API key)
5. Generate cost/latency analysis

### Expensive conditions

Opus compaction and full-context are not run by default. Edit `run_benchmark.sh` or run them individually:

```bash
python adapter.py --config configs/opus.json --data data/longmemeval_s_cleaned.json
python adapter.py --config configs/full-context.json --data data/longmemeval_s_cleaned.json
```

### Resume after interruption

All adapter runs support `--resume`, which skips already-completed instances:

```bash
python adapter.py --config configs/haiku.json --data data/longmemeval_s_cleaned.json --resume
```

## Output

Results are written to `results/<condition>/`:

- `predictions.jsonl` — Predictions in LongMemEval format (`{"question_id": "...", "hypothesis": "..."}`)
- `metrics.jsonl` — Per-instance cost, latency, token counts, compaction counts
- `memories.jsonl` — Final memory state for each instance (for qualitative analysis)

## Analysis

```bash
python analyze_results.py results/
python analyze_results.py results/ --format markdown
```

Produces:
- Cost & latency summary table
- Token usage summary
- Per-category cost breakdown
- Accuracy table (if evaluation has been run)

## Estimated Costs

| Condition | Est. Cost/Instance | Total (500) |
|-----------|-------------------|-------------|
| soul-haiku | ~$0.02-0.06 | $10-30 |
| soul-sonnet | ~$0.15-0.40 | $75-200 |
| soul-opus | ~$0.60-1.50 | $300-750 |
| full-context | ~$0.35 | $175 |
| no-memory | ~$0.001 | $0.50 |

## What Makes This Interesting

Even if overall accuracy is lower than RAG baselines, SOUL offers:
- **100x+ compression** (115k tokens → ~1.1k tokens)
- **No retrieval infrastructure** (no embeddings, no vector DB, no chunking)
- **O(1) read cost** (memory is already in context, no retrieval step)
- **Strong on Knowledge Updates** (compaction resolves contradictions by design)

If Knowledge Updates performance is competitive with full-context, that validates SOUL's core thesis: lossy compaction can preserve the information that matters most.

## Files

- `adapter.py` — Main adapter: processes instances through SOUL pipeline
- `compaction_prompt.py` — General-purpose compaction prompt (not code-specific)
- `run_benchmark.sh` — Orchestrates full benchmark run
- `analyze_results.py` — Cost/latency analysis + per-category breakdown
- `configs/` — One JSON config per experimental condition
- `data/` — LongMemEval dataset (downloaded by run_benchmark.sh)
- `results/` — Output predictions, metrics, memories
- `LongMemEval/` — Cloned evaluation scripts (by run_benchmark.sh)
