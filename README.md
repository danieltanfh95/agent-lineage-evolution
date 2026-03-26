# SOUL: Structured Oversight of Unified Lineage

[![DOI](https://zenodo.org/badge/DOI/PLACEHOLDER.svg)](https://doi.org/PLACEHOLDER)

A governance framework for persistent agent identity in Claude Code. SOUL addresses LLM agent memory degradation through rolling knowledge compaction, conscience-based audit loops, human-authored invariants, and hierarchical genome inheritance.

SOUL extends the [Agent Lineage Evolution (ALE)](https://danieltan.weblog.lol/2025/06/agent-lineage-evolution-a-novel-framework-for-managing-llm-agent-degradation) framework from episodic succession to continuous governance, leveraging Claude Code's native hooks system.

## Quick Start

```bash
./soul-init.sh
```

This creates the `.soul/` directory structure, installs hook scripts, and registers them in `.claude/settings.json`. Then:

1. Edit `.soul/SOUL.md` — fill in your agent's Identity section
2. Edit `.soul/invariants/*.md` — add your project's invariants
3. Start a `claude` session — the soul is injected automatically

## Documentation

- **[SOUL Framework Whitepaper](docs/soul-framework-whitepaper.md)** — Full technical description of the framework's architecture, design decisions, and relationship to prior work
- **[ALE Blog Post](docs/ale-blog-post-2025.md)** — The original Agent Lineage Evolution blog post (June 2025), the conceptual predecessor to SOUL
- **[Experiments](experiments/README.md)** — Empirical validation protocols and results for SOUL's core claims

## How It Works

SOUL is built on five pillars:

| Component | Purpose |
|-----------|---------|
| **SOUL.md** | Persistent agent identity — who it is, what it knows, what mistakes to avoid |
| **Invariants** | Human-authored, agent-immutable rules checked by the conscience |
| **Conscience** | A `Stop` hook that audits agent responses against invariants |
| **Genome Cascade** | Hierarchical knowledge inheritance (global → language → archetype → repo) |
| **Rolling Compaction** | LSM-tree-inspired knowledge refinement on `PostCompact` events |

## Repository Structure

```
.soul/                    # Framework implementation
  SOUL.md                 # Repo-level agent identity
  invariants/             # Human-authored rules
  hooks/                  # session-start.sh, conscience.sh, compact.sh
  config.json             # Conscience & compaction settings
  log/                    # Audit trail
.claude/                  # Claude Code config + generated skills
docs/                     # Whitepaper and ALE blog post
experiments/              # Empirical validation protocols and results
soul-init.sh              # One-command framework initialization
```

## Citation

If you use SOUL in your research or projects, please cite:

```bibtex
@software{tan_soul_2026,
  author = {Tan, Daniel},
  title = {SOUL: Structured Oversight of Unified Lineage},
  year = {2026},
  url = {https://github.com/g-daniel/agent-lineage-evolution},
  version = {1.0.0}
}
```

See also [CITATION.cff](CITATION.cff) for machine-readable citation metadata.

## License

[MIT](LICENSE)
