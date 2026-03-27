# SOUL: Persistent Memory for Claude Code Agents

*Daniel Tan — March 2026*

---

Claude Code forgets everything between sessions.

After 10 sessions of building an Express API — adding authentication, refactoring the database layer, swapping caching libraries, fixing performance bugs — I asked it 20 questions about the project it had just built. Without memory, it could answer 6 out of 20. With SOUL, it got all 20 right.

This is the problem SOUL solves: **persistent agent identity that survives across sessions**, not just memory, but who the agent is, what it knows, what mistakes to avoid, and rules it cannot break.

## The Memory Problem

Research shows a [39% performance drop](https://arxiv.org/abs/2505.06120) in multi-turn versus single-turn LLM scenarios. The longer the conversation, the worse the model performs. But the real pain isn't within a single session — it's across sessions.

Every time you start a new Claude Code session, you lose everything. Your agent doesn't remember:
- The architectural decisions you made last week
- The bug you fixed and the lesson you learned from it
- Your preference for plain language over jargon
- The fact that you already migrated from Redis to Memcached

Claude Code's built-in `MEMORY.md` helps, but it grows monotonically. After a few weeks, it's full of contradictions — old facts coexisting with new ones, stale references to deleted files, notes about resolved bugs. It becomes noise.

## What SOUL Does

SOUL (Structured Oversight of Unified Lineage) is a governance layer for Claude Code built on five pillars:

**1. SOUL.md — Agent Identity.** A structured document that tells the agent who it is, what it knows, and what mistakes its predecessors made. Loaded automatically at every session start.

**2. Invariants — Unbreakable Rules.** Human-authored rules the agent cannot violate. "Always confirm before modifying files." "Use plain language." "Never force-push." These are external to the agent and enforced by the conscience.

**3. Conscience — Automated Audit Loop.** A `Stop` hook that runs after every agent response, checking it against your invariants. Uses a lightweight model (Haiku) for cost efficiency, escalates to blocking on violations.

**4. Genome Cascade — Shared Knowledge.** Universal agent traits live in `~/.soul/genome/base.md` and apply across all your projects. Project-specific knowledge in `.soul/SOUL.md` overrides the general — like CSS specificity for agent knowledge.

**5. Rolling Compaction — Memory That Shrinks.** Instead of growing forever, SOUL.md is periodically compressed by an LLM that merges new knowledge, resolves contradictions, and prunes stale information. Like an LSM-tree for agent memory.

## The Results

### SOUL-Bench: Code Project Memory

I built a [benchmark](https://github.com/danieltanfh95/agent-lineage-evolution/tree/master/experiments/06-soul-bench) specifically for testing code project memory — 10 sessions of Express.js development with deliberate contradictions (Redis → Memcached), stale information (deleted endpoints), and knowledge updates (raw SQL → Knex ORM).

| Category | SOUL (Sonnet 4.6) | No Memory |
|----------|-------------------|-----------|
| Fact Retention (8 questions) | 8/8 | 0/8 |
| Contradiction Resolution (4) | 4/4 | 1/4 |
| Staleness Detection (2) | 2/2 | 2/2 |
| Abstention (3) | 3/3 | 3/3 |
| Predecessor Warnings (3) | 3/3 | 0/3 |
| **Overall** | **20/20 (100%)** | **6/20 (30%)** |

SOUL's compacted memory was 5.1k chars — smaller than this section of the blog post.

**Honest caveat:** Append-only memory (just concatenating session notes) also scored 20/20 at this scale. With only 10 short sessions (~10k chars total), there's not enough data to overflow the reader's context. SOUL's compression advantage becomes meaningful at larger scales.

### LongMemEval: Stress Test at Scale

To test at scale, I ran SOUL against [LongMemEval](https://arxiv.org/abs/2410.10813) (ICLR 2025), a benchmark with ~50 conversation sessions per instance (~500k chars). Here the compression story is dramatic:

**86x compression** — 500k chars of conversation compressed into 5.8k chars of memory, while retaining enough detail to answer 2-3 out of 5 factual recall questions.

This is preliminary (5 instances, not the full 500), but the compression quality is remarkable.

### The Model Matters More Than the Prompt

The most surprising finding: **Haiku 4.5 completely fails at compaction**. Given 50 sessions to compress, Haiku's "compacted" memory ballooned from 200 chars to 34,000 chars — it copies content instead of compressing. It's paradoxically *slower* than Opus because it generates 2.2x more output tokens.

| Model | Memory after 50 sessions | Behavior |
|-------|-------------------------|----------|
| Haiku 4.5 | ~28,000 chars (growing) | Broken — no compression |
| Sonnet 4.6 | ~5,800 chars (stable) | Reliable compression |
| Opus 4.6 | ~5,800 chars (stable) | Best quality |

Sonnet 4.6's extended thinking capability is the key — it can reason about what to keep and what to discard before generating output. SOUL now defaults to Sonnet 4.6 for compaction.

## Try It Now

### Install

```bash
npx skills add danieltanfh95/agent-lineage-evolution --skill soul
```

This installs SOUL as a Claude Code skill with automatic hooks — no manual configuration needed.

### Setup

In Claude Code, type:

```
/soul setup
```

SOUL asks you three questions:
1. What's your role? What kind of work do you do?
2. What rules should I always follow?
3. Any project knowledge I should remember?

That's it. SOUL is active. Every future session in this project will have your identity, rules, and knowledge loaded automatically. When context gets long, compaction fires automatically to keep knowledge fresh and compact.

### Other Commands

- `/soul remember our deploy target is AWS ECS` — save a specific fact
- `/soul update` — change your preferences
- `/soul show` — see current configuration

## Under the Hood

SOUL uses three [Claude Code hooks](https://code.claude.com/docs/en/hooks):

- **SessionStart** — assembles genome cascade + SOUL.md + invariants into the session context
- **Stop** — runs conscience audit against invariants (keyword-triggered + scheduled)
- **PostCompact** — compacts the session's knowledge into SOUL.md via a Sonnet 4.6 call

The hooks are declared in the skill's YAML frontmatter, so they activate automatically when the skill is installed — no `settings.json` editing required. The skill also includes scripts for compaction, conscience, and session assembly, all bundled in the `scripts/` directory.

For the full technical details, see the [whitepaper](https://github.com/danieltanfh95/agent-lineage-evolution/blob/master/docs/soul-framework-whitepaper.md).

## Background: ALE → SOUL

SOUL extends the [Agent Lineage Evolution (ALE)](https://danieltan.weblog.lol/2025/06/agent-lineage-evolution-a-novel-framework-for-managing-llm-agent-degradation) framework I published in June 2025. ALE introduced generational succession — when an agent degrades, it creates a structured handoff package for its successor. SOUL replaces episodic succession with continuous governance: instead of dying and passing knowledge on, the agent is continuously monitored and its knowledge continuously refined.

## What's Next

This is v1.0.0. Some honest limitations:

- **Append-only ties SOUL at small scale** — the compression advantage only shows when memory exceeds what fits easily in context
- **Instruction drift is real but not yet reproducible in benchmarks** — I observe Opus ignoring process instructions past ~150k tokens in interactive sessions, but couldn't reproduce this via `claude -p` with static system prompts
- **4 of 6 experiments have protocols but no results yet** — compaction vs append-only, conscience catch rate, genome cascade setup time, and quality trajectory are defined but unrun

The framework is open source under MIT. If you're interested in running the experiments, contributing scenarios, or adapting SOUL for your workflow, the [repo](https://github.com/danieltanfh95/agent-lineage-evolution) has everything you need.

---

*SOUL is available on [skills.sh](https://skills.sh) and [Zenodo](https://doi.org/PLACEHOLDER). Built entirely with Claude Code.*
