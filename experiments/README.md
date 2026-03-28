# SOUL Framework — Experiments

This directory contains seven experiments designed to empirically validate SOUL's core claims. Each experiment has a self-contained protocol, evaluation criteria, and scripts.

## Overview

| # | Experiment | Tests | Key Metric |
|---|-----------|-------|------------|
| 01 | [Compaction vs. Append-Only](01-compaction-vs-appendonly/) | Does rolling compaction reduce context poisoning? | Contradiction count, staleness, accuracy |
| 02 | [Conscience Catch Rate](02-conscience-catch-rate/) | Does the conscience detect invariant violations? | Precision, recall, F1 |
| 03 | [Genome Cascade Setup Time](03-genome-cascade-setup-time/) | Do pre-populated genomes reduce setup time? | Turns to completion |
| 04 | [Compaction Quality Trajectory](04-compaction-quality-trajectory/) | Does SOUL.md quality improve over compaction cycles? | Quality score trajectory |
| 05 | [SOUL vs. LongMemEval](05-longmemeval/) | Can lossy compaction support personal-memory recall? | LongMemEval accuracy, cost, compression ratio |
| 06 | [SOUL-Bench](06-soul-bench/) | Does SOUL retain code knowledge, resolve contradictions, prune stale info? | Per-category accuracy on 20 questions |
| 07 | [Knowledge Workers](07-knowledge-workers/) | Does SOUL improve experience for PMs and analysts? | Role awareness, rule compliance, preference updates |

## Running Experiments

Each experiment directory contains:
- `README.md` — Protocol description, hypothesis, and methodology
- Scripts (`.sh`) — Automation for setup and execution
- Evaluation criteria — Rubrics or scoring definitions

Results are written to `results/` within each experiment directory. Key results (predictions, metrics, memories) are committed for reproducibility.

## Prerequisites

- Claude Code CLI (`claude`) installed and authenticated
- Bash 4+ with `jq` available
- Git initialized in the working directory

## Relationship to Whitepaper

These experiments correspond to the questions posed in Section 7.5 of the [SOUL whitepaper](../docs/soul-framework-whitepaper.md):

1. Does rolling compaction actually reduce context poisoning over time? → Experiment 01
2. What is the optimal audit frequency (cost vs. violation catch rate)? → Experiment 02
3. Do genome cascades reduce setup time for new repositories? → Experiment 03
4. How do conscience violation rates correlate with agent output quality? → Experiment 04
5. Can SOUL's compaction compete on external benchmarks? → Experiment 05 (LongMemEval, ICLR 2025)
6. Does SOUL retain code project knowledge across sessions? → Experiment 06 (SOUL-Bench)
7. Does SOUL improve the experience for knowledge workers? → Experiment 07
