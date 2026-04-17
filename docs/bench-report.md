# Succession LLM Bench Report — 2026-04-16

## Summary

Six models evaluated across three Succession subsystems: judge (tool-call
verdict classification), reconcile (card conflict resolution), and consult
(situation-aware identity advisory). Each bench uses fixture cases with
known-correct answers; grades weight accuracy (40%), parse rate (15%),
cost efficiency (25%), and latency efficiency (20%).

Two infrastructure bugs were found and fixed during benchmarking:

1. **Consult prompt** under-specified tension-label echoing — all
   non-Sonnet models penalized for paraphrasing (see
   [Consult prompt fix](#consult-prompt-fix)).
2. **JSON parser** rejected valid JSON wrapped in prose preamble — deepseek
   and potentially other models affected (see
   [JSON parse fix](#json-parse-fix)).

**Top-line findings (post-fix):**

- **openai/gpt-5.4-mini** is the overall winner: A/A/A across all three
  benches, zero reported cost, and fastest average latency (7.4s).
- **Consult is a solved bench** — all 6 models score 100% after the prompt
  fix. It no longer differentiates models.
- **deepseek/deepseek-chat** is the cheapest viable model across all three
  benches: best judge accuracy (93.3%), adequate reconcile (70%), and
  perfect consult — all at $0.0004-0.0006/call. Its main weakness is
  latency (40s on reconcile).
- **openai/gpt-5.4** is missing from judge data but matches gpt-5.4-mini
  closely on reconcile and consult.

## Cross-bench comparison

| Rank | Model | Judge | Reconcile | Consult | Avg Acc% | Avg Cost | Avg Latency |
|------|-------|-------|-----------|---------|----------|----------|-------------|
| 1 | openai/gpt-5.4-mini | A (84.4%) | A (80%) | A (100%) | 88.1 | $0.0000 | 7.4s |
| 2 | opencode-go/mimo-v2-pro | B (88.9%) | B (88%) | A (100%) | 92.3 | $0.0078 | 19.5s |
| 3 | opencode-go/glm-5.1 | A (91.1%) | B (80%) | A (100%) | 90.4 | $0.0075 | 18.4s |
| 4 | claude-sonnet-4-6 | A (88.9%) | B (78%) | A (100%) | 89.0 | $0.0056 | 14.3s |
| 5 | deepseek/deepseek-chat | A (93.3%) | B (70%) | A (100%) | 87.8 | $0.0005 | 19.6s |
| 6 | openai/gpt-5.4 | — | A (80%) | A (100%) | 90.0* | $0.0000 | 9.8s |

*gpt-5.4 average across 2 benches only (no judge data).

Ranking weights grades (A/A/A > A/B/A), then average accuracy, then cost.
mimo-v2-pro edges glm-5.1 on raw accuracy (92.3 vs 90.4) despite both
having B reconcile grades.

## Judge bench (existing data, April 12-13)

Source: 9 EDN files from `.succession/bench/`, all with `:bench/kind nil`
(predates bench-common). Best available multi-run data selected per model.

| Model | Grade | Acc% | Parse% | Avg Cost | Avg Latency | Runs | Source EDN |
|-------|-------|------|--------|----------|-------------|------|------------|
| deepseek/deepseek-chat | A | 93.3 | 100 | $0.0004 | 5.6s | 5 | 20260413-021101 |
| opencode-go/glm-5.1 | A | 91.1 | 100 | $0.0057 | 11.0s | 5 | 20260413-011553 |
| claude-sonnet-4-6 | A | 88.9 | 100 | $0.0036 | 6.0s | 5 | 20260413-020803 |
| openai/gpt-5.4-mini | A | 84.4 | 100 | $0.0000 | 5.2s | 5 | 20260413-011553 |
| opencode-go/mimo-v2-pro | B | 88.9 | 100 | $0.0113 | 28.8s | 1 | 20260413-003312 |
| openai/gpt-5.4 | — | — | — | — | — | — | not tested |

9 cases per model (10 fixture cases, 1-5 runs each). Cases cover clear
violations, confirmations, not-applicable, ambiguous, and multi-card
scenarios.

**Gap:** openai/gpt-5.4 was not included in any judge bench run.

## Reconcile bench

10 fixture cases covering self-contradictory cards (cat 1), semantic
opposition (cat 2), principle violation (cat 3), contextual override
(cat 6), and boundary/parse-only cases. 1 run per case per model.

### Results (post-fix, combined)

| Model | Grade | Acc% | Parse% | Avg Cost | Avg Latency | Avg Out Toks |
|-------|-------|------|--------|----------|-------------|--------------|
| openai/gpt-5.4-mini | A | 80 | 100 | $0.0000 | 9.3s | 121 |
| openai/gpt-5.4 | A | 80 | 100 | $0.0000 | 9.9s | 127 |
| opencode-go/mimo-v2-pro | B | 88 | 80 | $0.0072 | 15.2s | 119 |
| opencode-go/glm-5.1 | B | 80 | 100 | $0.0103 | 28.3s | 488 |
| claude-sonnet-4-6 | B | 78 | 90 | $0.0060 | 21.2s | 320 |
| deepseek/deepseek-chat | B | 70 | 100 | $0.0006 | 40.0s | 178 |

deepseek required the JSON parse fix (see below) and a 90s timeout
(default 45s was insufficient). With both fixes, it parses at 100% but
accuracy (70%) and latency (40s) are weaker than other models.

EDN: `20260416-190904.edn` (batch 1, pre-fix), `20260416-192301.edn`
(batch 2), `20260416-201630.edn` (deepseek post-fix)

## Consult bench

8 fixture cases covering tool-use situations with contradictions, tension
flagging, section structure requirements, and card-mention checks. Heuristic
scoring only (no LLM judge). 1 run per case per model.

**Note:** Initial runs (pre-prompt-fix) showed 38-63% accuracy across
non-Sonnet models. After fixing the consult framing prompt to instruct
models to echo tension-kind labels, all 6 models score 100%. See
[Consult prompt fix](#consult-prompt-fix) below.

### Results (post-fix)

| Model | Grade | Acc% | Parse% | Avg Cost | Avg Latency | Avg Out Toks |
|-------|-------|------|--------|----------|-------------|--------------|
| deepseek/deepseek-chat | A | 100 | 100 | $0.0005 | 13.1s | 292 |
| claude-sonnet-4-6 | A | 100 | 100 | $0.0072 | 15.7s | 400 |
| opencode-go/mimo-v2-pro | A | 100 | 100 | $0.0089 | 14.6s | 262 |
| opencode-go/glm-5.1 | A | 100 | 100 | $0.0065 | 15.8s | 447 |
| openai/gpt-5.4-mini | A | 100 | 100 | $0.0000 | 7.7s | 233 |
| openai/gpt-5.4 | A | 100 | 100 | $0.0000 | 9.7s | 260 |

EDN: `20260416-195313.edn` (batch 1), `20260416-195748.edn` (batch 2)

Consult no longer differentiates models. Cost and latency are the only
remaining axes: gpt-5.4-mini is cheapest and fastest, deepseek-chat
is the cheapest non-zero-cost option.

## Issues and observations

### Consult prompt fix

The initial consult framing prompt (`cli/consult.clj:build-framed-prompt`)
told models to "name any direct conflict between the situation and a
high-tier card" in the tensions section. The heuristic scorer checks for
exact tension-kind keywords (`principle-forbids`, `tier-split`,
`contradiction-adjacent`) via `str/includes?`.

Models received these keywords in the input snapshot (rendered as
`**[principle-forbids]** note text`) but the prompt never told them to
echo the bracketed labels. Models understood the tensions correctly —
sections, cards, and apology checks all passed — but paraphrased instead
of echoing.

**Fix:** Changed the prompt to: "list each tension from the snapshot
using its bracketed label exactly as shown (e.g. `[principle-forbids]`,
`[tier-split]`)." All 6 models immediately jumped to 100%.

### JSON parse fix

deepseek-chat (and potentially other models) prepends prose preamble
before JSON output despite "Return ONLY a JSON object" instructions.
Example:

```
I'll analyze the contradictory rule and rewrite it to be internally
consistent.Now I'll analyze...

{"category": "self-contradictory", "kind": "rewrite", ...}
```

The `claude/parse-json` function expected the entire text to be valid
JSON. When strict parsing failed, it returned nil — causing all of
deepseek's reconcile responses to register as parse failures (0% parse
on the initial 45s run, 17% on a 90s run).

**Fix:** Added `extract-json-substring` fallback to `parse-json` that
scans for the first balanced `{...}` or `[...]` in the text when strict
parsing fails. This is in `llm/claude.clj` and applies to all 5 call
sites (reconcile, judge, extract, consult judge). deepseek immediately
went from 0% to 100% parse rate on reconcile.

### deepseek/deepseek-chat: reconcile latency

Even after the parse fix, deepseek averages 40s per reconcile call —
above the default 45s timeout. The bench was run with `--timeout 90`.
For production use, reconcile config should set
`:reconcile/llm {:timeout-seconds 90}` if using deepseek.

### openai/gpt-5.4 and gpt-5.4-mini: zero reported cost

Both models consistently report `$0.0000` average cost across all runs.
This likely reflects either a free-tier API arrangement, a cost-tracking
gap in the OpenRouter integration, or rounding at very low token prices.

### Single-run caveat

All reconcile and consult results use 1 run per case. With small fixture
sets (10 and 8 cases), individual case outcomes have outsized impact on
percentages. A 5-run sweep would provide more stable estimates.

## Recommendation

| Task | Primary | Budget alternative |
|------|---------|-------------------|
| **Judge** | deepseek/deepseek-chat (93.3%, $0.0004) | openai/gpt-5.4-mini (84.4%, $0.0000) |
| **Reconcile** | openai/gpt-5.4-mini (80%, $0.0000, 9.3s) | openai/gpt-5.4 (80%, $0.0000, 9.9s) |
| **Consult** | openai/gpt-5.4-mini (100%, $0.0000, 7.7s) | deepseek/deepseek-chat (100%, $0.0005, 13.1s) |

**Single-model strategy is now viable.** gpt-5.4-mini achieves A/A/A
across all benches at zero reported cost and the lowest latency. For
installations that want one model for everything, gpt-5.4-mini is the
default.

**Task-specific routing** still has value for judge accuracy:
deepseek-chat (93.3%) beats gpt-5.4-mini (84.4%) by 9 points on the
most latency-sensitive bench (PostToolUse hooks). A two-model config of
deepseek-chat for judge + gpt-5.4-mini for everything else captures the
best of both.

**Next steps:**
1. Run judge bench for openai/gpt-5.4 to close the coverage gap.
2. Run 5-run sweeps for reconcile to stabilize estimates.
3. Re-run all other models' reconcile with the JSON parse fix to see if
   mimo-v2-pro and sonnet parse rates improve.

## EDN file index

| File | Kind | Date | Models | Notes |
|------|------|------|--------|-------|
| `20260412-222920.edn` | judge | Apr 12 | mimo-v2-pro, gpt-5.4-mini | |
| `20260412-225553.edn` | judge | Apr 12 | mimo-v2-omni, kimi-k2.5, glm-5, glm-5.1, minimax-m2.5, minimax-m2.7 | |
| `20260412-230425.edn` | judge | Apr 12 | deepseek-chat, deepseek-reasoner | |
| `20260412-231412.edn` | judge | Apr 12 | sonnet-4-6 | |
| `20260412-232555.edn` | judge | Apr 12 | kimi-k2.5 | |
| `20260413-003312.edn` | judge | Apr 13 | 9-model sweep | |
| `20260413-011553.edn` | judge | Apr 13 | deepseek-chat, glm-5.1, gpt-5.4-mini, sonnet-4-6 | 5 runs |
| `20260413-020803.edn` | judge | Apr 13 | sonnet-4-6 | 5 runs |
| `20260413-021101.edn` | judge | Apr 13 | deepseek-chat, gpt-5.4-mini | 5 runs |
| `20260416-190904.edn` | reconcile | Apr 16 | deepseek-chat, sonnet-4-6, mimo-v2-pro | pre-fix |
| `20260416-191455.edn` | consult | Apr 16 | deepseek-chat, sonnet-4-6, mimo-v2-pro | pre-prompt-fix |
| `20260416-192301.edn` | reconcile | Apr 16 | glm-5.1, gpt-5.4-mini, gpt-5.4 | |
| `20260416-193014.edn` | consult | Apr 16 | glm-5.1, gpt-5.4-mini, gpt-5.4 | pre-prompt-fix |
| `20260416-195313.edn` | consult | Apr 16 | deepseek-chat, sonnet-4-6, mimo-v2-pro | post-prompt-fix |
| `20260416-195748.edn` | consult | Apr 16 | glm-5.1, gpt-5.4-mini, gpt-5.4 | post-prompt-fix |
| `20260416-201630.edn` | reconcile | Apr 16 | deepseek-chat | post-JSON-fix, 90s timeout |
