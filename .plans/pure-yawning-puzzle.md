# Plan: Blog Post Extension + Zenodo Whitepaper

## Context

Two deliverables needed:
1. **Extend the informal primer blog post** (`docs/ale-blog-post-primer-2026.md`) — currently 18 lines, needs to become a full blog post that bridges ALE (2025) to Succession (2026), drawing from the research brief
2. **New Zenodo whitepaper** (`docs/succession-whitepaper-2026.md`) — full academic paper covering the three-generation lineage (ALE → SOUL → Succession), framed around **guided behavioral evolution**, suitable for DOI publication

The blog post is the informal primer; the whitepaper doubles as the formal blog post and Zenodo publication.

---

## Deliverable 1: Blog Post (`docs/ale-blog-post-primer-2026.md`)

**Tone:** Informal, personal, opinionated. Keep Ghost in the Shell, cultivation novels, memes. Not academic.

### Proposed structure (extending existing content)

**§1 "Sessions Need to Die" (lines 1-7, exists — minor edits)**
- Keep Ghost in the Shell opening
- Keep Anthropic "cap context window" validation
- Fix image reference, tighten prose

**§2 "The 1M Context Paradox" (lines 7-8, expand)**
- Why Anthropic shipped 1M context (compaction losing context complaints)
- The gap: more context ≠ better context
- Brief mention of the memory field's response (Mem0, RAG, graph memory) — acknowledge they're good at what they do

**§3 "Lucid Dreaming" (lines 16-18, promote to full section)**
- Develop the metaphor fully: memory systems manage the dream, not the dreamer
- What memory can't transfer: strategy, failure patterns, communication calibration, meta-cognition
- The compression vs. distillation distinction (include the table from research brief §4, rewritten in blog voice)
- Key line: "Memory extends the context window. Succession extends the agent."

**§4 "The Field Is Starting to Notice" (new, brief ~10 lines)**
- Cherry-pick 3-4 signals from research brief §5:
  - ReMe (procedural memory — closest parallel)
  - Dynamic Personality (ACL 2025 — agents have behavioral profiles worth managing)
  - AI Behavioral Science (agents as empirical behavioral entities)
- Frame: converging on the edge but nobody has crossed into generational identity with human oversight

**§5 "The Soul and the Cultivation Novel" (lines 9-13, keep + refine)**
- Cultivation novel metaphor → skills imprinted on the soul survive death
- Connect to technical mechanism: behavioral patterns extracted from corrections survive compaction and session death

**§6 "What Succession Actually Does" (lines 14-15, expand significantly)**
- Three enforcement tiers in plain language
- Correction detection: "it watches you correct the agent and learns"
- Four knowledge categories mapped to the "what memory can't transfer" list from §3
- Skill extraction: replayable workflow bundles
- Brief evolution timeline: ALE (manual) → SOUL (governance) → Succession (extraction + enforcement)

**§7 "Does It Work?" (new, short)**
- SOUL-Bench: 20/20 vs 6/20 no-memory (one sentence)
- LongMemEval: 86x compression, Sonnet/Opus stable at ~6k chars (one sentence)
- Honest caveat: append-only ties at small scale
- Link to whitepaper for details

**§8 "Try It / Links" (new, short)**
- Repo link, install command
- Link to whitepaper on Zenodo
- Link to original ALE blog post

### Content sources
- Research brief §3-5 → §2, §3, §4
- Succession architecture §4-6, §10 → §6
- SOUL whitepaper §5.5 + SOUL-Bench results → §7
- Existing lines 1-18 → §1, §2, §5 (keep and extend)

---

## Deliverable 2: Zenodo Whitepaper (`docs/succession-whitepaper-2026.md`)

**Title:** "Guided Behavioral Evolution for LLM Agents: A Three-Generation Framework for Behavioral Continuity"

**Authors:** Daniel Tan Fook Hao, Moki Chen Meng Jin

**Format:** Markdown, academic structure (abstract, numbered sections, references). Zenodo accepts markdown rendered as PDF.

### Outline

**Abstract** (~200 words, write fresh)
- Problem: agents lose behavioral identity across sessions; memory preserves facts not operational patterns
- Contribution: three-generation framework introducing generational succession, continuous governance, mechanical behavioral enforcement
- Results: SOUL-Bench 20/20 vs 6/20; LongMemEval 86x compression
- Position: first framework combining human-shepherded generational identity with mechanical enforcement

**1. Introduction**
- 1.1 The Behavioral Continuity Problem
  - Multi-turn degradation (Laban et al. 2025, 39% drop)
  - Lost in the Middle (Liu et al. 2023)
  - Instruction drift at ~150k tokens (empirical observation)
  - Source: ALE paper §1.1-1.3
- 1.2 Memory Is Not Enough: The Lucid Dreaming Problem
  - Memory taxonomy: compress, store, retrieve (write-manage-read loop)
  - What memory can't transfer: strategy, failure patterns, relational calibration, meta-cognition
  - Source: research brief §3
- 1.3 Compression vs. Distillation
  - The key table (research brief §4) — elevated to a core framing device
  - "Memory extends the context window. Succession extends the agent."
- 1.4 Contributions
  - Three-generation lineage with each generation addressing predecessor limitations
  - Human shepherd as a genuine differentiator from fully-automated approaches
  - Experimental validation on two benchmarks

**2. Related Work**
- 2.1 Self-Evolving Agent Taxonomy (Gao et al. 2025 survey)
  - Model evolution, memory evolution, prompt optimization
  - Human oversight is the gap
- 2.2 Prompt Evolution
  - Promptbreeder (ICLR 2024): automated, no identity
  - SCOPE (Dec 2025): dual-stream, no succession
  - Agent-Pro (ACL 2024): single-agent, no lineage
- 2.3 Memory Systems
  - Context-resident, RAG, graph, reflective (survey arXiv:2603.07670)
  - Mem0 SOTA: 26% accuracy gain, 90% token savings
  - Procedural memory gap: ReMe, MemEvolve
- 2.4 Emerging Signals
  - AI Behavioral Science (arXiv:2506.06366)
  - Dynamic Personality (ACL 2025 Findings)
- Source: research brief §1-5, high reuse with light editing

**3. Framework Evolution**
- 3.1 Generation 1: ALE (June 2025)
  - Dual-process meta-prompt, succession packages, measurable triggers
  - Limitation: manual transfer, single-chat only
  - Source: ALE paper §2, condensed to ~1 page
- 3.2 Generation 2: SOUL (March 2026)
  - Five pillars: SOUL.md, invariants, conscience, genome cascade, rolling compaction
  - Key decisions: external conscience, invariants as tests, LSM-tree compaction
  - Model selection findings: Haiku fails (~28k chars), Sonnet/Opus stable (~6k)
  - Source: SOUL whitepaper §2 + §5, condensed to ~1.5 pages
- 3.3 Generation 3: Succession (April 2026)
  - Three enforcement tiers: mechanical ($0), semantic (~$0.005), advisory (re-injection)
  - Correction detection pipeline (keyword → Sonnet confirm → flag)
  - Four knowledge categories
  - CSS-like cascade (global → project, explicit overrides)
  - Skill extraction
  - Effectiveness tracking (JSONL log → counter materialization → promotion)
  - Source: succession-architecture §4-10, restructured as narrative ~2 pages

**4. The Human Shepherd**
- Why human oversight matters (all prior work is fully automated)
- Division of labor: humans define what correctness means, system enforces it
- Invariants as tests: mirrors tests vs. code relationship
- Review workflows: retrospective transcript analysis, rule review candidates
- Source: fresh synthesis from SOUL whitepaper §5.2 + research brief §1

**5. Experimental Evaluation**
- 5.1 SOUL-Bench
  - Design: 10-session Express.js scenario, 20 questions, 5 categories
  - Results table: SOUL Sonnet 20/20, SOUL Haiku 19/20, append-only 20/20, no-memory 6/20
  - Memory sizes: Sonnet 5,151 chars, append-only 9,745 chars (1.9x compression)
  - Limitation: append-only ties at this scale
  - Source: exp 06 results
- 5.2 LongMemEval
  - Design: ICLR 2025 benchmark, ~50 sessions/instance, ~500k chars
  - Compression: Sonnet 5,784 chars, Opus 6,052 chars, Haiku 21,568 chars
  - Haiku failure as evidence for model capability as primary determinant
  - Limitation: accuracy not yet scored (partial runs only)
  - Source: exp 05 results
- 5.3 Extraction Quality (preliminary)
  - L1-obvious: F1=1.0 (4/4 TP, 0 FP)
  - L5-false-positives: keyword-only F1=0.0 (demonstrates need for semantic tier)
  - L2-L4 pending
  - Source: exp 06 extraction results
- 5.4 Drift Reproduction Attempt
  - System-prompt padding did not reproduce drift (0-300k tokens, Opus)
  - Methodology limitation: real drift needs interactive context, not padding
  - Source: exp 06 drift results
- 5.5 Limitations
  - 4 of 7 experiments protocol-only
  - Small sample sizes on LongMemEval
  - No accuracy scores yet on LongMemEval
  - Drift not reproducible in synthetic setup

**6. Comparison**
- Extended table from succession-architecture §11 with added "Memory Systems" column:

| | Memory Systems | ALE (2025) | SOUL (2026) | Succession (2026) |
|---|---|---|---|---|
| Core idea | Fact retrieval | Episodic succession | Continuous governance | Behavioral extraction |
| Unit | Facts, events | Handoff package | SOUL.md (monolithic) | Individual rule files |
| Enforcement | None | N/A | Conscience (LLM audit) | Mechanical + semantic + advisory |
| Drift mitigation | None | N/A | SessionStart (once) | Periodic re-injection + mechanical |
| Human role | None | Shepherd (manual) | Invariant author | Rule reviewer |
| Cost/session | Varies | N/A | ~$0.50-2.00 | ~$0.05-0.20 |

**7. Future Work**
- Remaining experiments (drift reproduction, L2-L4 extraction, knowledge workers)
- Cross-rule inference (Datalog)
- Team/organizational genome sharing
- Adaptation beyond Claude Code
- Source: SOUL whitepaper §7 + succession-architecture §12

**8. Conclusion** (write fresh)
- Each generation addressed predecessor limitations: manual→automated, episodic→continuous, expensive→mechanical
- The field builds better memory plumbing; this work builds identity infrastructure
- Open source, reproducible, minimal scaffolding

**References** (~18 refs, merged from all docs)

### Content reuse estimate
- ~35% high reuse (related work from research brief, ALE/SOUL descriptions)
- ~35% synthesis (restructuring existing technical content into narrative)
- ~30% fresh writing (abstract, introduction framing, human shepherd section, conclusion)

### Estimated length: ~4000-5000 words

---

## Implementation sequence

1. **Blog post first** — smaller, forces narrative framing decisions that carry into the whitepaper
2. **Whitepaper second** — draws on blog post framing, adds academic rigor and experimental detail

---

## Critical files

| File | Role |
|------|------|
| `docs/ale-blog-post-primer-2026.md` | Blog post to extend |
| `docs/succession-whitepaper-2026.md` | New whitepaper to create |
| `docs/succession-research-brief-v2.md` | Primary source for related work + lucid dreaming critique |
| `docs/succession-architecture.md` | Succession technical content |
| `docs/archive/soul-framework-whitepaper.md` | SOUL architecture + experiments |
| `docs/ale-blog-post-2025.md` | ALE content + style reference |
| `experiments/06-soul-bench/` | SOUL-Bench results |
| `experiments/05-longmemeval/` | LongMemEval results |

---

## Verification

- Blog post: read through for narrative flow, check all research citations match the brief
- Whitepaper: verify all experimental numbers match committed results in `experiments/`
- Both: ensure consistent terminology (Succession, not SOUL or Imprint, for the current system)
- Whitepaper: check Zenodo formatting requirements (title, authors, abstract, references)
