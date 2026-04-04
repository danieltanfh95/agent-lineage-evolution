# Guided Behavioral Evolution for LLM Agents: A Three-Generation Framework for Behavioral Continuity

**Daniel Tan Fook Hao, Moki Chen Meng Jin**

April 2026

---

## Abstract

LLM-based agents suffer from behavioral amnesia: corrections, preferences, and operational patterns are lost across session boundaries, while instructions degrade within sessions as context windows fill. Existing approaches focus on memory augmentation — compressing, indexing, and retrieving factual content — but cannot transfer the operational patterns that constitute an agent's behavioral identity: how it approaches problems, what failure patterns it avoids, how it calibrates communication, and which heuristics proved reliable. We present a three-generation framework for guided behavioral evolution that addresses this gap. The first generation, Agent Lineage Evolution (ALE, 2025), introduced generational succession through manual meta-prompts. The second, SOUL (2026), formalized continuous governance with rolling compaction, an external conscience, and hierarchical knowledge inheritance. The third, Succession (2026), introduced mechanical behavioral enforcement immune to instruction drift, organic correction extraction, and CSS-like rule cascading. Across generations, the core insight persists: agent knowledge should be distilled into behavioral identity, not merely compressed into retrievable facts. On SOUL-Bench, a purpose-built evaluation, the framework's compaction layer achieves 20/20 knowledge retention versus 6/20 for a no-memory baseline. On LongMemEval (ICLR 2025), compaction achieves an 86x compression ratio while maintaining stable memory size. Succession-specific benchmarks (enforcement, extraction, behavioral transfer) are in progress. The framework is the first to combine human-shepherded oversight with mechanical behavioral enforcement for LLM agent continuity.

---

## 1. Introduction

### 1.1 The Behavioral Continuity Problem

Multi-turn LLM interactions exhibit measurable performance degradation. Microsoft Research found that all tested state-of-the-art LLMs "exhibit significantly lower performance in multi-turn conversations than single-turn, with an average drop of 39% across six generation tasks" in analysis of over 200,000 simulated conversations [1]. The "Lost in the Middle" phenomenon demonstrates that performance "degrades significantly when changing the position of relevant information," with U-shaped performance curves across multiple model architectures [2]. Code-generation models show pervasive repetition patterns, with 20 identified repetition patterns across 19 state-of-the-art models [3].

These degradation patterns interact with two forms of behavioral amnesia in agents that persist across sessions:

1. **Cross-session amnesia**: Corrections and preferences are forgotten when a session ends. Users repeat the same instructions across sessions.
2. **Intra-session drift**: Instructions injected at session start (via system prompts, CLAUDE.md files, or similar mechanisms) lose influence as the context window fills. Empirically, instruction drift — where the agent stops following process instructions — is observed around 150k tokens in interactive Claude Code sessions, well below the 1M token context limit.

Previous approaches address cross-session amnesia through persistent configuration files but not intra-session drift — these files are injected once and buried under subsequent context.

### 1.2 Memory Is Not Enough: The Lucid Dreaming Problem

The dominant approach to agent continuity is memory augmentation: storing conversational content in external databases and retrieving it at inference time. The field formalizes agent memory as a write–manage–read loop [4]. The main families include context-resident compression (MemGPT, LightMem), RAG-based retrieval (LoCoMo), structured graph memory (Mem0, Zep, A-MEM), and reflective summarization (MemoryBank). The state of the art — Mem0 [5] — achieves 26% higher accuracy over OpenAI's native memory on the LOCOMO benchmark, 91% lower p95 latency, and 90% token cost reduction by extracting salient facts into a vector+graph store.

These systems are impressive at what they do. The problem is that what they do is insufficient for behavioral continuity.

All memory approaches work on the *content* of past experience, not the *experiencer*. They ask: "What happened that the agent should remember?" They compress, index, and surface facts, events, and entities — the conversational record. When retrieved, these facts re-enter the context window and the agent reasons from them as if reliving the experience. The agent is still fundamentally the same stateless entity, given a curated flashback.

This is lucid dreaming in a precise sense: the agent navigates a managed reconstruction of past experience, aware enough to use it, but not transformed by it. The dream ends; the dreamer resets.

What memory systems cannot transfer across the reset boundary:

- **Strategy** — *how* the agent learned to approach problems (workflow patterns, methodologies)
- **Failure inheritance** — *what patterns of failure* it fell into and should avoid (anti-patterns, things that went wrong)
- **Relational calibration** — *how it adapted its communication style* to a specific user (tone, verbosity, explanation depth)
- **Meta-cognition** — *which heuristics proved reliable* versus which sounded plausible but failed

These are operational patterns, not facts. They live in behavior, not content.

### 1.3 From Compression to Distillation

The distinction between memory augmentation and behavioral inheritance is the distinction between compression and distillation. Memory systems compress past experience into retrievable facts — they preserve signal from the past. Behavioral inheritance distills past experience into operational patterns — it transforms signal into capability for the future. Where memory asks "what should the agent remember?", behavioral inheritance asks "what should the agent *become*?"

This paper presents Succession, a framework that implements behavioral inheritance for LLM coding agents. Succession hooks into an agent's lifecycle events (session start, tool calls, response completion), detects user corrections organically, extracts behavioral rules classified by enforcement tier and knowledge category, and enforces them mechanically — outside the agent's context window, immune to instruction drift. Each rule is a single file with metadata tracking its scope, enforcement mechanism, and effectiveness. Rules cascade from global to project scope, enabling personal preferences to coexist with project-specific overrides.

The key architectural difference from memory systems is where enforcement happens. Memory systems inject retrieved content into the context window, where it competes with other context for the model's attention and degrades as the window fills. Succession enforces critical rules mechanically at the tool-call level, before the model even sees them. Advisory rules that must live in-context are periodically re-injected rather than set once, fighting the drift that buries static configuration files under growing context.

The two approaches are complementary, not substitutes. Agents can and should use both — memory for factual recall, behavioral inheritance for operational continuity. Memory is runtime infrastructure. Behavioral inheritance is identity infrastructure.

### 1.4 Contributions

This paper presents the evolution of a framework across three generations, each addressing specific limitations of its predecessor:

1. **Agent Lineage Evolution (ALE, 2025)**: Introduced generational succession through structured meta-prompts, demonstrating that behavioral knowledge can be distilled and transferred across agent generations.
2. **SOUL (2026)**: Formalized continuous governance for agentic tools with rolling compaction, an external conscience audit loop, hierarchical knowledge inheritance, and human-authored invariants.
3. **Succession (2026)**: Introduced three-tier enforcement (mechanical, semantic, advisory) immune to instruction drift, organic correction extraction, CSS-like rule cascading, and effectiveness tracking.

Across generations, the framework maintains a core differentiator: **human-shepherded oversight** in a field where nearly all work on agent self-evolution is fully automated.

---

## 2. Related Work

### 2.1 Self-Evolving Agent Taxonomy

The 2025 survey "A Survey of Self-Evolving Agents: On Path to Artificial Super Intelligence" [6] taxonomizes the field into three improvement axes: model evolution (updating internal weights via self-generated supervision), memory evolution (accumulating and pruning knowledge across sessions), and prompt optimization (refining instructions that govern behavior without touching weights).

Nearly all existing work across these axes is fully automated — no human in the loop. The human shepherd role that this framework maintains is a genuine gap in the literature.

### 2.2 Prompt Evolution

**Promptbreeder** (Fernando et al., Google DeepMind, ICLR 2024) [7] evolves populations of task-prompts across generations. Crucially, it also evolves the mutation-prompts — the instructions used to mutate task-prompts — making it self-referential. It outperforms hand-crafted Chain-of-Thought strategies on arithmetic and commonsense benchmarks. However, it uses fully automated selection via fitness scoring on training sets. No generational succession triggered by degradation, no behavioral identity being preserved — only task performance being optimized.

**SCOPE** (Pei et al., December 2025) [8] frames context management as an online optimization problem and synthesizes guidelines from execution traces via a dual-stream mechanism: one stream handles immediate error correction, the other evolves long-term principles. The long-term principles stream is the closest analogue to behavioral inheritance, but there is no succession event, no human review, and no concept of behavioral identity passing between generations.

**Agent-Pro** (ACL 2024) [9] evolves agent policy via reflection and depth-first search across belief states. It remains single-agent optimization with no succession or lineage concept.

### 2.3 Memory Systems

The 2025 survey on memory mechanisms for autonomous LLM agents [4] formalizes the write–manage–read loop and catalogues approaches across context-resident compression, RAG-based retrieval, structured/graph memory, and reflective summarization.

Mem0 [5] represents the current state of the art, achieving significant improvements in accuracy, latency, and token cost over both full-context and native memory approaches. Its core mechanism — extracting salient facts and maintaining them in a vector+graph store — is representative of the broader pattern: memory systems operate on factual content.

The research itself is starting to recognize the boundary between factual and procedural memory. **ReMe** ("Remember Me, Refine Me", December 2025) [10] introduces a "dynamic procedural memory framework for experience-driven agent evolution," explicitly distinguishing procedural memory (how to act) from declarative/episodic memory (what happened). This is the closest the memory field gets to behavioral inheritance, but it remains within a single agent instance and treats procedural memory as another retrieval problem.

**MemEvolve** ("Meta-Evolution of Agent Memory Systems", December 2025) [11] attempts to evolve the memory system itself, not just its contents — closer in spirit to this framework, but still focused on memory architecture rather than behavioral identity.

**The procedural memory explosion.** Since mid-2025, procedural memory has emerged as a recognized subfield addressing the gap between factual recall and operational knowledge. **Mem^p** (Fang et al., August 2025) [21] provides a systematic analysis of procedural memory construction, retrieval, and update across trajectories, with a key finding that procedural memory built from a stronger model transfers to weaker models with substantial gains — the closest existing work to behavioral inheritance, though it frames transfer as a retrieval problem within single tasks rather than across session lineages. **MACLA** (Forouzandeh et al., December 2025, AAMAS 2026 Oral) [22] introduces hierarchical procedural memory with Bayesian reliability tracking, compressing 2,851 trajectories into 187 reusable procedures (15:1 ratio). The Bayesian confidence scoring parallels Succession's effectiveness tracking, though MACLA operates within a single agent rather than across generations. **LEGOMem** (Han et al., October 2025, AAMAS 2026) [23] decomposes trajectories into modular procedural memory units allocated across agents, demonstrating that smaller LMs with procedural memory narrow the performance gap with stronger agents. Wheeler and Jeunen [24] argue that procedural memory alone is insufficient for "wicked" environments, requiring semantic memory and associative learning — a theoretical grounding for why behavioral inheritance needs to operate across multiple knowledge types, not just procedure replay.

**Behavioral consistency as a measurable problem.** Two recent papers provide methodology and measurements directly relevant to the drift that behavioral inheritance aims to prevent. Bhardwaj [25] proposes Agent Behavioral Contracts — a formal specification framework with preconditions, invariants, governance, and recovery — bounding behavioral drift to D* < 0.27 with 88–100% constraint compliance. Mehta [26] measures behavioral consistency across 3,000 runs on Llama 70B, GPT-4o, and Claude Sonnet 4.5, finding that consistent behavior yields 80–92% accuracy versus 25–60% for inconsistent tasks, with 69% of behavioral divergence occurring at step 2 of multi-step tasks. These quantify the problem that inheritance aims to solve.

**Benchmarks are evolving beyond recall.** MemoryAgentBench [27] (ICLR 2026) tests four cognitive competencies including test-time learning and selective forgetting — a step beyond pure factual recall. Evo-Memory [28] (Google DeepMind, November 2025) introduces a streaming benchmark with the ReMem pipeline, testing memory under continuous updates.

Despite this procedural memory explosion, no existing paper addresses behavioral inheritance across agent *lineages* — the transfer of operational patterns from one session or instance to a successor, with human curation of what gets inherited. Mem^p's cross-model transfer is the closest, but it operates within single tasks, not across session boundaries. Comprehensive memory benchmarks still do not test for behavioral transfer across agent instances [4]. That measurement gap is itself evidence that the field is not yet thinking generationally.

### 2.4 Emerging Signals

**AI Behavioral Science** (arXiv:2506.06366, 2025) [12] proposes studying agents as behavioral entities whose "actions, adaptations, and social patterns can be empirically studied." If agent behavior is a first-class scientific object, then behavioral inheritance deserves first-class tooling.

**Dynamic Personality in LLM Agents** (Zeng et al., ACL 2025 Findings) [13] empirically showed that agent personality traits evolve across generational Prisoner's Dilemma scenarios, with measurable drift and adaptation. This provides the closest empirical evidence that agents possess a behavioral profile worth managing across generations.

---

## 3. Framework Evolution

### 3.1 Generation 1: Agent Lineage Evolution (June 2025)

ALE [14] introduced generational succession for LLM agents: rather than treating agents as persistent entities, it conceptualized them as temporary instantiations within an evolving lineage. A dual-process meta-prompt system provided continuous lifecycle monitoring alongside task execution, with measurable succession triggers (context utilization at 75%, two consecutive low-quality responses, repetition of documented predecessor failures). When triggered, the agent generated a structured succession package — context distillation, failure warnings, behavioral overrides — for its successor.

ALE validated that behavioral knowledge can be distilled and transferred across agent generations, but had three key limitations: it required manual transfer (humans copied meta-prompts between chat sessions), it relied on self-assessment for degradation detection (unreliable in a degraded agent [15, 16]), and its episodic model required agent "death" as the trigger for knowledge transfer.

### 3.2 Generation 2: SOUL (March 2026)

SOUL (Structured Oversight of Unified Lineage) [17] formalized ALE's principles for Claude Code's hooks system, replacing episodic succession with continuous governance. It introduced five pillars: a persistent identity document (SOUL.md) injected at session start, human-authored invariants enforced as test-like assertions, an external conscience audit loop running on a separate model invocation, hierarchical knowledge inheritance (genome cascade) inspired by CSS specificity, and rolling compaction treating knowledge as a Log-Structured Merge-tree.

The key design shift from ALE was externalizing monitoring. Instead of self-assessment, SOUL used a separate model invocation for conscience audits, eliminating the degraded-agent-judging-itself problem. Invariants were human-authored and agent-immutable, mirroring the principle that tests should be independent of the code they verify.

**Model selection findings.** Benchmarking against LongMemEval [18] revealed that model capability is the primary determinant of compaction quality. Haiku 4.5 could not reason about what to discard — memory grew to ~28,000 characters instead of compressing. A two-stage extract-then-merge pipeline improved Haiku to ~15,000 characters but still exhibited unbounded growth. Sonnet 4.6 and Opus 4.6 both stabilized at ~5,800 characters. Sonnet 4.6's extended thinking capability appears to be the key differentiator, achieving near-Opus quality at lower cost.

**Limitations.** SOUL's conscience added ~$0.50–2.00 per session in audit costs. The monolithic SOUL.md resisted granular editing. Invariants were human-authored only — the system could not learn from organic corrections. And all enforcement depended on context injection, making it vulnerable to the same instruction drift it aimed to prevent.

### 3.3 Generation 3: Succession (April 2026)

Succession addresses SOUL's limitations through three innovations: mechanical enforcement immune to drift, organic correction extraction, and granular one-rule-one-file storage with CSS-like cascading.

#### 3.3.1 Three Enforcement Tiers

**Tier 1 — Mechanical (PreToolUse command hook).** A script evaluates tool calls against compiled rules (`tool-rules.json`) using regex patterns and exact-match predicates. It can block tools entirely (`block_tool: Agent`), block bash commands matching patterns (`block_bash_pattern: "git push.*(--force|-f)"`), or require prior actions (`require_prior_read: true`). Cost: $0. Latency: ~10–50ms. Deterministic and completely immune to instruction drift because it runs outside the agent's context window.

**Tier 2 — Semantic (PreToolUse prompt hook).** A Claude Code prompt hook sends the tool call plus compiled semantic rules to Sonnet, which returns `{ok: true/false, reason: "..."}`. Example rules: "Use Edit tool instead of sed when modifying source files." Cost: ~$0.005 per evaluation. Non-deterministic but drift-immune (runs externally). Limited to evaluating the current tool call, not the broader conversation.

**Tier 3 — Advisory (Stop hook re-injection).** Every N turns, the Stop hook reads `advisory-summary.md` and returns it as `additionalContext`, refreshing rules in the agent's context. Example rules: "Prefer concise responses." "Ask before making large changes." These are periodically re-injected rather than set once, fighting the drift that buries CLAUDE.md-style injections under growing context. Additionally, a Stop prompt hook audits completed responses against advisory rules, catching violations that PreToolUse cannot (e.g., "don't add trailing summaries").

Total cost per session: ~$0.05–0.20, a 10x reduction from SOUL's conscience.

#### 3.3.2 Correction Detection Pipeline

The Stop hook runs a three-tier correction detection pipeline on every turn:

1. **Keyword scan** (free): Stop words ("no,", "don't", "stop", "instead", "wrong") trigger further analysis.
2. **Sonnet micro-prompt** (~$0.005): "Is this user message correcting agent behavior? YES/NO." Confirmed corrections set a flag.
3. **Extraction threshold reduction**: The correction flag reduces the extraction threshold by 5x, ensuring the next extraction includes this correction context.

When the extraction threshold is reached, the system reads the transcript window (up to 200KB), sends it to Sonnet with an extraction prompt, and for each extracted pattern: classifies it by enforcement tier (mechanical/semantic/advisory) and knowledge category, writes an individual rule file with YAML frontmatter, and logs the extraction.

#### 3.3.3 Four Knowledge Categories

Each rule is classified into one of four categories, corresponding to the operational patterns that memory systems cannot transfer:

| Category | What it captures | Example |
|----------|-----------------|---------|
| **Strategy** | How the agent approaches problems | "Always plan before editing" |
| **Failure inheritance** | Patterns of failure to avoid | "Never force-push without confirmation" |
| **Relational calibration** | Communication style adaptation | "Prefer concise responses, don't summarize" |
| **Meta-cognition** | Which heuristics proved reliable | "Sonnet is more reliable than Haiku for semantic checks" |

#### 3.3.4 CSS-Like Cascade Resolution

Rules cascade from global to project scope:

1. Load `~/.succession/rules/*.md` (global)
2. Load `.succession/rules/*.md` (project)
3. Same `id`: project wins
4. Explicit `overrides: [id]`: remove overridden rules
5. Filter to `enabled: true`
6. Partition by enforcement tier
7. Compile to three artifacts: `tool-rules.json` (mechanical), `semantic-rules.md` (semantic), `advisory-summary.md` (advisory)

This two-level cascade with explicit overrides covers the actual conflict scenarios encountered in practice. The rule file format is designed to be Datalog-compatible if cross-rule inference is needed later.

#### 3.3.5 Effectiveness Tracking

Every rule carries effectiveness counters in its frontmatter:

```yaml
effectiveness:
  times_followed: 18
  times_violated: 2
  times_overridden: 0
  last_evaluated: 2026-04-01T10:00:00Z
```

Mechanical rules log follow/violate events to an append-only JSONL file. When a user correction matches an existing rule, `rule_violated` is logged against that rule instead of creating a duplicate. Counters are periodically materialized into rule file frontmatter. During cascade resolution, rules with >50% violation rate (10+ evaluations) are flagged for review, and advisory rules with >80% follow rate are flagged for promotion to semantic enforcement.

#### 3.3.6 Retrospective Analysis and Skill Extraction

Succession supports post-hoc extraction from past session transcripts, enabling users to analyze what went wrong and extract rules without having had the hooks active during the session. Batch mode adds degradation analysis, identifying turns where agent behavior noticeably worsened and possible causes.

Skill extraction bundles observed workflow patterns into replayable SKILL.md files containing trigger conditions, step sequences, domain knowledge, and task-specific rules. Skills follow the same cascade as rules.

---

## 4. The Human Shepherd

A consistent differentiator across all three generations is the human shepherd role. The 2025 survey on self-evolving agents [6] confirms that nearly all existing work on agent self-evolution is fully automated. This framework deliberately maintains human oversight at several points:

**In ALE:** The human authorized succession events and reviewed succession packages before transferring them to new sessions.

**In SOUL:** Humans author invariants — the behavioral rules the conscience enforces. The agent cannot modify its own invariants. This mirrors the software engineering principle that tests should be independent of the code they verify. Compaction results are optionally committed to git for human review.

**In Succession:** Humans review extracted rules, decide on promotions and overrides, and conduct retrospective transcript analysis. The system extracts candidate rules organically; the human curates them. Effectiveness tracking provides data for these review decisions.

The division of labor is intentional: *machines observe and enforce; humans define and curate*. Research on LLM self-correction supports this design: multiple surveys confirm that LLMs generally cannot reliably correct their own mistakes without external feedback [15, 16]. The human shepherd provides that external feedback loop.

### 4.1 Automate First, Human Curates

A subtlety of this design is that the human role is not authorship but curation. Across the three generations, the framework has progressively automated more of the pipeline while keeping the human as the final reviewer:

- **ALE** required the human to trigger succession, review the succession package, and manually transfer it to a new session. The human was the primary operator.
- **SOUL** automated compaction and conscience auditing but still required humans to author invariants from scratch. The system could detect violations but could not learn new rules from organic corrections.
- **Succession** automates correction detection, rule extraction, enforcement tier classification, and effectiveness tracking. The human's role shrinks to reviewing extracted rules, approving promotions, and conducting retrospective analysis.

This is distinct from both fully-automated systems (Promptbreeder evolves prompts without human review; MACLA builds procedural memory via automated Bayesian selection) and human-first systems (manually authored CLAUDE.md, hand-written invariants). The design principle is: let the LLM do the work first — extract the pattern, classify the enforcement tier, write the rule file — and let the human fix what the LLM got wrong afterward.

The effectiveness tracking system exists specifically to support this curation role. When the human reviews rules, they have data: how often each rule was followed, how often violated, how often overridden. Rules with high violation rates are flagged for review. Advisory rules with consistently high follow rates are flagged for promotion to semantic enforcement. The human does not need to remember which rules are working — the system tracks it.

This approach is honest about current limitations. Extraction quality is imperfect: keyword-only detection produces unacceptable false positive rates (F1=0.0 on false-positive traps in preliminary testing), which is precisely why the semantic confirmation step and human review remain necessary. As extraction quality improves — through better models, better prompts, or accumulated effectiveness data — the human role may shrink further, but the framework is designed so that it does not need to disappear entirely.

---

## 5. Experimental Evaluation

The experiments presented here validate the **compaction and knowledge retention layer** that is shared between SOUL and Succession. Both SOUL-Bench and LongMemEval test SOUL's rolling compaction pipeline — the component responsible for compressing session knowledge into structured memory. Succession's enforcement tiers (mechanical, semantic, advisory), correction detection pipeline, and rule extraction have not yet been benchmarked end-to-end. A Succession-specific evaluation is in progress and will be reported separately (see §5.6).

### 5.1 SOUL-Bench: Code Project Memory

**Design.** SOUL-Bench is a purpose-built evaluation testing the compaction layer's knowledge retention on code project memory. It uses a synthetic 10-session Express.js API development scenario with 20 questions across five categories: fact retention (8 questions), contradiction resolution (4), staleness detection (2), abstention (3), and predecessor warnings (3).

**Conditions.** Four conditions were tested using SOUL's compaction pipeline: SOUL with Sonnet 4.6, SOUL with Haiku 4.5, append-only memory (no compaction), and no memory.

**Results.**

| Category | SOUL Sonnet | SOUL Haiku | Append-Only | No Memory |
|---|---|---|---|---|
| Retention (8) | 8/8 | 8/8 | 8/8 | 0/8 |
| Contradiction (4) | 4/4 | 4/4 | 4/4 | 1/4 |
| Staleness (2) | 2/2 | 2/2 | 2/2 | 2/2 |
| Abstention (3) | 3/3 | 3/3 | 3/3 | 3/3 |
| Warning (3) | 3/3 | 2/3 | 3/3 | 0/3 |
| **Overall** | **20/20 (100%)** | **19/20 (95%)** | **20/20 (100%)** | **6/20 (30%)** |

Memory sizes after 10 sessions: SOUL Sonnet 5,151 chars (1.9x compression from 9,745 chars raw), SOUL Haiku 5,831 chars, append-only 9,745 chars.

SOUL Sonnet compaction growth across sessions: 779 → 1,238 → 1,802 → 2,162 → 2,682 → 2,838 → 3,496 → 4,132 → 4,790 → 5,127 chars.

**Limitation.** Append-only memory also scored 20/20 at this scale (~10k chars of accumulated text). This indicates that the compression advantage becomes meaningful only at larger scales where append-only memory exceeds practical context limits. SOUL-Bench Layer 2 (planned) will test whether proactive compaction prevents the instruction drift observed at high context depths (>150k tokens).

### 5.2 LongMemEval: Long-Term Memory at Scale

**Design.** LongMemEval [18] is an ICLR 2025 benchmark testing chat assistants' long-term memory across multi-session conversations (~50 sessions per instance, ~500k chars of conversation).

**Results** (partial runs, 2–5 questions per condition):

| Condition | Avg. final memory size | Compactions | Behavior |
|---|---|---|---|
| No memory | 0 chars | 0 | Baseline |
| Haiku 4.5 | 21,568 chars | 98 | Copies instead of compressing — unbounded growth |
| Haiku 4.5 (extract→merge) | 15,428 chars | 0 | Better than raw Haiku, still growing |
| Opus 4.6 | 6,052 chars | 205 | Reliable compression, stable |
| Sonnet 4.6 | 5,784 chars | 255 | Reliable compression, stable |

Sonnet 4.6 achieved an approximately 86x compression ratio (from ~500k chars of conversation to ~5.8k chars of structured memory).

**Key finding.** Model capability is the primary determinant of compaction quality, more important than prompt engineering or pipeline architecture. Haiku cannot reason about what to discard when given large inputs; it defaults to preserving everything. Sonnet 4.6's extended thinking capability appears to be the key differentiator.

**Limitation.** Accuracy scores (correct answer rate) have not been computed for these runs — the LongMemEval scorer was not run to completion. The compression metrics are reliable; the recall metrics are pending.

### 5.3 Extraction Quality (Preliminary)

Layer 2 of SOUL-Bench tests correction extraction quality using SOUL's `conscience.sh` extraction pipeline across five difficulty levels. The extraction architecture is similar to Succession's bb-based pipeline but differs in implementation:

| Level | Condition | Recall | Precision | F1 |
|---|---|---|---|---|
| L1 — Obvious corrections | Append-only | 1.0 | 1.0 | 1.0 |
| L5 — False positive traps | Keyword-only | 1.0 | 0.0 | 0.0 |

L1 demonstrates that the extraction pipeline correctly identifies all obvious corrections with no false positives. L5 demonstrates that keyword-only detection (Tier 1 of the correction pipeline) produces unacceptable false positive rates, validating the need for the semantic confirmation step (Tier 2). Results for L2–L4 (implicit corrections, multi-turn patterns, buried corrections) are pending.

### 5.4 Instruction Drift Reproduction

An attempt to reproduce instruction drift synthetically used system-prompt padding (0–300k tokens of filler) with Opus 4.6:

| Context depth | Compliance score |
|---|---|
| 0 tokens | 2/3 |
| 50k tokens | 3/3 |
| 100k tokens | 2/3 |
| 150k tokens | 3/3 |
| 200k tokens | 3/3 |
| 300k tokens | 3/3 |

No degradation trend was observed. This is a methodology limitation: system-prompt padding does not reproduce the kind of drift observed in genuine multi-turn interactive sessions, where accumulated reasoning artifacts and conversational history create qualitatively different context from synthetic padding. Reproducing instruction drift in a controlled experimental setting remains an open problem.

### 5.5 Limitations of Current Experiments

- All experiments test SOUL's compaction pipeline, not Succession's full enforcement and extraction pipeline.
- Four of seven planned experiments remain protocol-only (no committed results).
- LongMemEval runs are partial (2–5 questions per condition) with no accuracy scores.
- SOUL-Bench uses a single scenario (Express.js); additional scenarios (Python CLI, user corrections) are defined but not yet run.
- Extraction quality is only measured at L1 and L5; L2–L4 are pending.
- Instruction drift was not reproducible in the synthetic setup.

### 5.6 Planned: Succession Pipeline Evaluation

A Succession-specific evaluation is in progress to test the components that differentiate it from SOUL:

- **Mechanical enforcement catch rate**: Does the PreToolUse hook actually block violations that advisory rules miss? Measured by running sessions with and without mechanical enforcement active, counting violations that reach the user.
- **Correction extraction quality**: Run L1–L5 extraction tests using Succession's bb-based pipeline with the full three-tier correction detection (keyword scan → semantic confirmation → extraction). Compare precision and recall against SOUL's `conscience.sh` baseline.
- **End-to-end behavioral transfer**: Does an agent with inherited Succession rules *behave differently* on subsequent tasks, compared to an agent with only factual memory? This is the most important test — it directly validates the behavioral inheritance claim.
- **A/B comparison**: SOUL conscience vs. Succession enforcement on identical session transcripts, measuring cost, catch rate, and false positive rate.

---

## 6. Comparison

| | Memory Systems | ALE (2025) | SOUL (2026) | Succession (2026) |
|---|---|---|---|---|
| **Core idea** | Fact retrieval | Episodic succession | Continuous governance | Behavioral extraction |
| **Unit of knowledge** | Facts, events, entities | Succession package | SOUL.md (monolithic) | Individual rule files |
| **Enforcement** | None | N/A | Conscience (LLM audit) | Mechanical + semantic + advisory |
| **Drift mitigation** | None | N/A | SessionStart injection (once) | Periodic re-injection + mechanical |
| **Knowledge storage** | Vector DB / graph | Handoff text | Rolling compaction (LSM-tree) | One file per rule + YAML frontmatter |
| **Cascade** | N/A | N/A | Genome hierarchy (4 levels) | Two levels (global → project) |
| **Human role** | None | Shepherd (manual transfer) | Invariant author | Rule reviewer + retrospective analyst |
| **Learning mechanism** | Automated extraction | Agent self-report | Conscience + compaction | Correction detection + extraction |
| **Cost per session** | Varies | N/A | ~$0.50–2.00 | ~$0.05–0.20 |
| **Drift immunity** | No | No | No (context injection) | Yes (mechanical + semantic tiers) |

Each generation addressed specific limitations of its predecessor: ALE eliminated manual prompt engineering but required manual transfer and self-assessment. SOUL eliminated both but was expensive and vulnerable to drift. Succession eliminated drift vulnerability through mechanical enforcement and reduced cost by 10x through targeted extraction rather than comprehensive auditing.

---

## 7. Future Work

**Succession pipeline evaluation.** The most immediate priority is benchmarking Succession's full pipeline — mechanical enforcement, semantic checks, correction extraction, and behavioral transfer — as described in §5.6. The current experiments validate only the shared compaction layer.

**Remaining compaction experiments.** Four of seven compaction-focused experiments remain protocol-only. Priority targets include: reproducing instruction drift in a controlled setting (the current synthetic approach failed), running L2–L4 extraction quality tests, completing the Python CLI and user corrections scenarios for SOUL-Bench, and running the knowledge worker experiments (PMs and data analysts).

**Cross-rule inference.** Rules are currently independent. A Datalog resolver could add transitive inference ("If React then TypeScript" + "If TypeScript then strict mode" → "If React then strict mode"), though the actual frequency of such cross-rule dependencies in practice is unknown.

**Team and organizational adoption.** Global rules (`~/.succession/rules/`) could be distributed via shared repositories, analogous to shared ESLint configs. This would enable organizational behavioral patterns to propagate to all team members' agents.

**Beyond Claude Code.** The current implementation is specific to Claude Code's hook system. Adaptation to other agentic frameworks (Cursor, Windsurf, custom agents) requires abstracting the hook interface. The rule file format and cascade resolution logic are framework-agnostic; only the hook integration layer is specific.

**Automated shepherd assistance.** The human shepherd role could be augmented with ML-assisted review: flagging rules that conflict, suggesting promotions based on effectiveness data, and summarizing behavioral drift across sessions.

---

## 8. Conclusion

This paper presents a three-generation framework for guided behavioral evolution of LLM agents. The core insight — that agent knowledge should be distilled into behavioral identity rather than merely compressed into retrievable facts — has persisted across all three generations while the implementation has matured from manual prompt engineering to mechanical enforcement.

Each generation addressed the limitations of its predecessor: ALE replaced manual prompt engineering with structured succession packages. SOUL replaced episodic succession with continuous governance and externalized monitoring. Succession replaced expensive LLM auditing with mechanical enforcement immune to instruction drift, reducing cost by 10x while adding organic correction extraction and effectiveness tracking.

The framework occupies a position distinct from both memory systems (which preserve factual content) and fully automated prompt optimization (which lacks human oversight). Memory is runtime infrastructure. Behavioral inheritance is identity infrastructure. The two are complementary — agents benefit from both — but they operate at different layers and address different problems.

The field of agent memory has built increasingly sophisticated plumbing to give agents better access to their past. This is valuable and necessary. But it does not address the deeper question: who is the agent, and how does that identity develop over time? This framework proposes one answer: through guided behavioral evolution, where humans define what the agent should become and machines enforce and refine that identity across sessions.

The framework is open source and available at https://github.com/danieltanfh95/agent-lineage-evolution.

---

## References

[1] Laban, P., Hayashi, H., Zhou, Y., & Neville, J. (2025). LLMs Get Lost In Multi-Turn Conversation. *Microsoft Research*. arXiv:2505.06120.

[2] Liu, N. F., Lin, K., Hewitt, J., Paranjape, A., Bevilacqua, M., Petroni, F., & Liang, P. (2023). Lost in the Middle: How Language Models Use Long Contexts. arXiv:2307.03172.

[3] Chen, X., et al. (2025). Code Copycat Conundrum: Demystifying Repetition in LLM-based Code Generation. arXiv:2504.12608.

[4] Memory for Autonomous LLM Agents: A Survey. (2025). arXiv:2603.07670.

[5] Chhikara, P., et al. (2025). Mem0: Building Production-Ready AI Agents with Scalable Long-Term Memory. arXiv:2504.19413.

[6] Gao, Y., et al. (2025). A Survey of Self-Evolving Agents: On Path to Artificial Super Intelligence. arXiv:2507.21046.

[7] Fernando, C., et al. (2024). Promptbreeder: Self-Referential Self-Improvement via Prompt Evolution. *ICLR 2024*. arXiv:2309.16797.

[8] Pei, J., et al. (2025). SCOPE: Synthesizing Contextual Optimization for Prompt Evolution. arXiv:2512.15374.

[9] Zhang, J., et al. (2024). Agent-Pro: Learning to Evolve via Policy-Level Reflection and Optimization. *ACL 2024*.

[10] ReMe: Remember Me, Refine Me — A Dynamic Procedural Memory Framework for Experience-Driven Agent Evolution. (2025).

[11] MemEvolve: Meta-Evolution of Agent Memory Systems. (2025).

[12] AI Agent Behavioral Science. (2025). arXiv:2506.06366.

[13] Zeng, J., et al. (2025). Dynamic Personality in LLM Agents. *ACL 2025 Findings*.

[14] Tan, D., & Chen, M. (2025). Agent Lineage Evolution: A Novel Framework for Managing LLM Agent Degradation. https://danieltan.weblog.lol/2025/06/agent-lineage-evolution-a-novel-framework-for-managing-llm-agent-degradation

[15] Kamoi, R., Zhang, Y., Zhang, N., Han, J., & Zhang, R. (2024). When Can LLMs Actually Correct Their Own Mistakes? A Critical Survey of Self-Correction of LLMs. *Transactions of the Association for Computational Linguistics*, 12, 1417–1440.

[16] Pan, L., et al. (2024). Automatically Correcting Large Language Models: Surveying the Landscape of Diverse Automated Correction Strategies. *Transactions of the Association for Computational Linguistics*.

[17] Tan, D. (2026). SOUL: Structured Oversight of Unified Lineage — A Governance Framework for Persistent Agent Identity in Claude Code. https://github.com/danieltanfh95/agent-lineage-evolution

[18] Wu, D., et al. (2025). LongMemEval: Benchmarking Chat Assistants on Long-Term Interactive Memory. *ICLR 2025*. arXiv:2410.10813.

[19] Kosmyna, N., & Hauptmann, E. (2025). Your Brain on ChatGPT: Accumulation of Cognitive Debt when Using an AI Assistant for Essay Writing Task. *MIT Media Lab*. https://www.brainonllm.com

[20] Gulcehre, C., et al. (2023). Reinforced Self-Training (ReST) for Language Modeling. arXiv:2308.08998.

[21] Fang, Y., Liang, Z., Wang, X., Wu, S., Qiao, Y., Xie, P., Huang, F., Chen, H., & Zhang, Y. (2025). Mem^p: Exploring Agent Procedural Memory. arXiv:2508.06433.

[22] Forouzandeh, S., Peng, Y., Moradi, P., Yu, T., & Jalili, M. (2025). MACLA: Learning Hierarchical Procedural Memory for LLM Agents through Bayesian Selection and Contrastive Refinement. *AAMAS 2026*. arXiv:2512.18950.

[23] Han, S., Couturier, A., Madrigal Diaz, J., Zhang, Y., Ruhle, T., & Rajmohan, S. (2025). LEGOMem: Modular Procedural Memory for Multi-agent LLM Systems. *AAMAS 2026*. arXiv:2510.04851.

[24] Wheeler, R. & Jeunen, O. (2025). Procedural Memory Is Not All You Need: Bridging Cognitive Gaps in LLM-Based Agents. *ACM UMAP '25 Workshop*. arXiv:2505.03434.

[25] Bhardwaj, A. (2026). Agent Behavioral Contracts: Formal Specification and Runtime Enforcement. arXiv:2602.22302.

[26] Mehta, R. (2026). When Agents Disagree With Themselves: Measuring Behavioral Consistency. arXiv:2602.11619.

[27] Hu, Y., Wang, X., & McAuley, J. (2025). MemoryAgentBench: Benchmarking LLM Agents on Diverse Memory Tasks. *ICLR 2026*. arXiv:2507.05257.

[28] Wei, J., et al. (2025). Evo-Memory: Streaming Benchmark for Evaluating Memory in LLM Agents. *Google DeepMind*. arXiv:2511.20857.
