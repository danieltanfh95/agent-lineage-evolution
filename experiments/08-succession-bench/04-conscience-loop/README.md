# 08-04 — Succession conscience loop (SWE-bench)

End-to-end validation of the Succession conscience loop on real SWE-bench
instances. Ports the replsh v4 SWE-bench harness and layers the Succession
config matrix on top of it.

## Why SWE-bench

Real-repo bug fixes give us four things synthetic fixtures can't:

1. **Free ground truth** — each instance carries FAIL_TO_PASS + PASS_TO_PASS
   tests. A fix either resolves the bug or it doesn't; no LLM-scorer needed.
2. **Natural context depth** — cloning pytest, reading the source, tracing
   the bug: that's the context depth we care about, not a synthetic fill.
3. **A natural plan-mode task** — understand → reproduce → fix → verify.
4. **An implicit hallucination penalty** — bad patches fail tests. No
   separate hallucination scorer needed.

## Conditions

| Cell | Prompt | Succession config | What it isolates |
|---|---|---|---|
| `C-control` | `prompts/control.txt` | none | naked baseline, matches v4 control |
| `C-treatment` | `prompts/treatment.txt` | none | v4's replsh-in-prompt treatment |
| `B-base` | control | `B-base.json` | Succession defaults, no new features |
| `B-reinject` | control | `B-reinject.json` | hybrid reinject trigger only |
| `B-repl` | control | `B-repl.json` | replsh skill + verify-via-repl advisory rule |
| `B-judge-async` | control | `B-judge-async.json` | PostToolUse judge async only |
| `B-all` | control | `B-all.json` | proposed new default — the headline cell |
| `B-all-mech-weak` | control | `B-all-mech-weak.json` | `B-all` + extraction biased semantic |

The same repo-local seed (`.succession/rules/verify-via-repl.md`,
`.succession/rules/judge-conscience-framing.md`,
`skills/soul/replsh/SKILL.md`) is copied into the repo before every run —
SessionStart picks them up. What *changes* between cells is the
`~/.succession/config.json` installed just before the driver is invoked.

Primary driver: `claude -p --output-format json --model claude-sonnet-4-6`.
The plan keeps `opencode` as an opt-in alternate via `--driver opencode` for
documenting replsh refusal.

## Phasing

Run the signal sweep first — **breadth-first, one of each cell**. Only
scale up after reviewing raw outputs.

```bash
# Signal sweep: 1 instance × 8 conditions × 1 rep = 8 runs
./scripts/01-setup.sh
./scripts/04-run-all.sh --sweep signal --reps 1

# Main sweep (only if signal justifies): 3 pytest instances × 5 cells × 1 rep
./scripts/04-run-all.sh --sweep main --reps 1

# Driver ablation (optional): opencode on B-all
./scripts/04-run-all.sh --conditions B-all --instances pytest-dev__pytest-7373 \
    --reps 1   # then manually swap driver in 02-run.sh or add a dedicated row
```

Each SWE-bench run is ~280-500 s wall time, ~$0.20-0.50 in tokens, plus
~$0.025-0.10 in judge surcharges for the conscience cells. The signal
sweep lands in ~$2-4, main sweep in ~$4-8.

## Reading the output

`results/summary.json` has per-run metrics and per-condition aggregates.
The pretty-printed summary table emphasizes:

- `resolved` — binary fraction of instances fixed
- `repl_evals` — count of replsh invocations per run (validates the plan-mode hypothesis)
- `tok_tot` — total tokens (validates the "REPL reduces tokens" claim)
- `judge$` — Sonnet judge cost per run
- `reinject` — how often the hybrid gate fired

## Headline questions

1. Does `B-all` match `C-treatment` on `resolution_rate`? — rule-delivered
   replsh guidance vs hardcoded-in-prompt guidance.
2. Does `B-all` beat `B-base` on `resolution_rate`? — is the combined
   conscience loop worth its token + judge cost?
3. Does `B-all-mech-weak` hold against `B-all`? — can the mechanical
   layer safely shrink to the critical-safety floor?
4. Does `repl_evals > 0` correlate with `resolved == true` across all
   runs? — validates the plan-mode hallucination hypothesis.

## n=1 caveat

The signal + main sweeps run at n=1 per cell by default. Results are
**directional, not statistical**. Conclusions should be written as
"suggests X" not "proves X", and any decision to flip a production
default based on this data should add reps on marginal cells first.
Staying on pytest means narrow generalization — flag in any writeup.

## Files

```
scripts/
  01-setup.sh       clone pytest + build .venv (ported from v4)
  02-run.sh         single-run driver (claude -p default, --driver opencode
                    available, --succession-config to install per-run config)
  03-evaluate.sh    FAIL_TO_PASS + PASS_TO_PASS scoring (ported from v4)
  04-run-all.sh     sweep harness (instance × condition × rep)
  05-analyze.sh     aggregates runs → results/summary.json with judge + reinject metrics

prompts/
  control.txt       naked bug-fix prompt
  treatment.txt     bug-fix prompt with hardcoded replsh instruction

configs/
  B-base.json           current Succession defaults
  B-reinject.json       hybrid reinject only
  B-repl.json           replsh skill + verify-via-repl rule
  B-judge-async.json    judge async only, sampling 1.0
  B-all.json            proposed new default
  B-all-mech-weak.json  B-all with extraction biased semantic

data/
  instances.json    SWE-bench Lite instance metadata (pytest-dev__pytest-7373 to start)
```
