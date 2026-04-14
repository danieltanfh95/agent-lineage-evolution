# Succession — Identity Cycle for AI Coding Agents

[![DOI](https://zenodo.org/badge/DOI/10.5281/zenodo.19437321.svg)](https://doi.org/10.5281/zenodo.19437321)

Succession is a behavioral-memory system for AI coding agents. It captures
corrections and preferences across sessions as **identity cards**, then refreshes
them back into the agent's context while it works — adjacent to each tool call,
so the rules stay inside the attention window instead of drifting out of it.

Successor to [SOUL](docs/archive/soul-framework-whitepaper.md) and
[ALE](docs/ale-blog-post-2025.md).

## The problem

LLM agents have two amnesias:

1. **Cross-session.** Corrections made in one session are forgotten when it ends.
2. **Intra-session drift.** Rules injected at session start lose influence as
   context fills. Around 150k tokens, Opus visibly stops following instructions
   it acknowledged earlier.

CLAUDE.md, system prompts, and `additionalContext` at SessionStart address the
first but not the second — they are delivered once, then buried.

## How it works

Succession runs as a set of Claude Code hooks that maintain a per-project
**identity store** under `.succession/identity/`. Three-tier card model:

- **Principle** — inviolable (`git commit --no-verify`, destroying user data, …)
- **Rule** — default behavior with justified exceptions
- **Ethic** — aspirational character

Each card has a weight computed from observations (how often it fires, how
recent, whether it was followed or violated). Promotion and demotion between
tiers use hysteresis so cards don't flap.

The load-bearing channel is the **PostToolUse refresh gate** — a compact reminder
of the most salient cards, emitted as `hookSpecificOutput.additionalContext`
after each tool call, gated by turn count and byte threshold so it does not spam.
See [Finding 1](docs/archive/succession-findings-2026.md) for the empirical basis: on
pytest-dev/pytest-5103, adjacent-to-now refresh produced 18 productive
`replsh eval` calls where CLAUDE.md-only produced 0.

Cards are updated through three parallel pipelines:

- **Deterministic** — PostToolUse fingerprint-matches tool calls against card
  invocation signatures, writing `:invoked` observations.
- **Async conscience judge** — PostToolUse enqueues a `:judge` job on the
  filesystem-backed queue under `.succession/staging/jobs/`. A single
  `succession worker drain` process LLM-judges the just-completed tool call
  against active cards and writes verdict observations.
- **Stop-time reconcile** — at session Stop, pure detectors check for
  contradictions (tier conflicts, pairwise opposites, orphan archetypes); a
  `:llm-reconcile` job on the same queue handles the ambiguous residual. The
  worker is one process shared by both lanes and self-exits when idle.

Promotions are **only** applied at PreCompact, under a filesystem lock:
observations fold into weights, weights trigger tier changes, the old promoted
tree is snapshotted to `.succession/archive/{ts}/`, the new tree is written
atomically.

## Install

Requires [babashka](https://github.com/babashka/babashka) and
[bbin](https://github.com/babashka/bbin):

```bash
bash < <(curl -s https://raw.githubusercontent.com/babashka/babashka/master/install)
bash < <(curl -s https://raw.githubusercontent.com/babashka/bbin/master/install)
```

Install the `succession` binary:

```bash
git clone https://github.com/danieltanfh95/agent-lineage-evolution
cd agent-lineage-evolution
bbin install .
```

Then in any project you want to instrument:

```bash
succession install
```

This writes (all idempotent):

- `.claude/skills/succession-consult/SKILL.md` — tells the agent when to
  self-consult its identity
- `.succession/config.edn` — starter config
- `.succession/identity/promoted/{principle,rule,ethic}/` — empty card tiers
- `.succession/{observations,staging,archive,contradictions,judge}/`
- `.claude/settings.local.json` hook entries for all six Claude Code events,
  using `succession hook <event>` (preserves any existing non-Succession hooks)

Migrate old rule-cascade YAML files into cards:

```bash
succession import .succession/rules
```

## CLI

```bash
succession consult "<situation>"   # reflective self-consult
succession replay <transcript>     # re-run hooks over a jsonl
succession config validate         # check config.edn
succession identity-diff           # diff promoted vs archive snapshot
succession show                    # print live promoted identity
succession queue <op>              # inspect/recover async job queue
succession compact                 # manually promote staged deltas
succession staging <op>            # inspect/prune staging directories
succession bench                   # judge regression/cost/latency bench
```

## Directory layout

```
bb.edn
src/succession/
  core.clj              # entry dispatcher (hooks + CLI subcommands)
  config.clj            # default config
  domain/               # pure: card, observation, weight, tier, reconcile,
                        #        consult, render, salience, rollup
  store/                # disk I/O: cards, observations, staging, sessions,
                        #           archive, contradictions, jobs, locks, paths
  domain/queue.clj      # pure: sort-jobs, idle?, job->result
  llm/                  # LLM I/O: judge, extract, reconcile, claude
  hook/                 # six Claude Code hook entry points
  worker/drain.clj      # async job-queue drain worker (core.async pipeline)
  cli/                  # consult, replay, config-validate, install,
                        #   identity-diff, import
test/succession/        # 171 tests, 469 assertions
docs/
  MANUAL.md               # every CLI command, flag, and hook contract
  ARCHITECTURE.md         # layers, weight formula, data flow
  HOOKS.md                # per-hook deep dive
  PRIOR_ART.md            # landscape survey and where Succession fits
  archive/                # SOUL + ALE predecessors, 2026 whitepaper + findings
experiments/              # empirical validation (pytest-5103, LongMemEval, …)
```

## Documentation

- **[MANUAL.md](docs/MANUAL.md)** — every CLI command, flag, and hook contract
- **[ARCHITECTURE.md](docs/ARCHITECTURE.md)** — layers, weight formula, data flow
- **[HOOKS.md](docs/HOOKS.md)** — per-hook deep dive with stdin/stdout contracts
- **[PRIOR_ART.md](docs/PRIOR_ART.md)** — landscape survey and where Succession fits

**Historical:**

- [2026 whitepaper](docs/archive/succession-whitepaper-2026.md) — design rationale at launch time (pre-Phase-4)
- [Conscience-loop findings](docs/archive/succession-findings-2026.md) — the 18-0 pytest-5103 experiment
- [ALE 2025 blog post](docs/ale-blog-post-2025.md) — the predecessor framework

## Prior work

1. **ALE (2025)** — Agent Lineage Evolution: episodic succession via
   hand-authored meta-prompts.
2. **SOUL (2026)** — Structured Oversight of Unified Lineage: continuous
   governance via conscience audit loops and rolling compaction.
3. **Succession (2026)** — Identity cycle. Cards, observations, weight-driven
   tier promotion, PostToolUse refresh as the load-bearing delivery channel.

## Citation

```bibtex
@software{tan_succession_2026,
  author = {Tan, Daniel},
  title = {Succession: Identity Cycle for AI Coding Agents},
  year = {2026},
  url = {https://github.com/danieltanfh95/agent-lineage-evolution},
  version = {3.0.0}
}
```

## License

[MIT](LICENSE)
