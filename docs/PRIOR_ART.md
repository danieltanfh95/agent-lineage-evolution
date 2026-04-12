# Prior Art & Landscape

Survey of existing work in the adjacent spaces that Succession touches —
memory systems, procedural memory, prompt evolution, rule/policy systems,
and behavioral-science framings. Conducted 2026-04, post-Phase-4 rename.

## The gap

No existing tool combines an **observation log with human-curated card
text** delivered via **adjacent-to-now refresh** (PostToolUse, not
SessionStart). Memory systems recall; they do not reshape. Prompt
optimizers evolve text without a human in the loop. Rule systems inject
once at front-of-context and drift at depth. Succession is complementary
to all three and does not replace any of them.

## Memory systems — the "lucid dreaming" framing

Memory systems remember what happened. They do not observe the agent's
behavior against rules and re-inject corrections adjacent to the frame
where the behavior was wrong. They recall facts; they do not reshape
identity.

**[MemGPT](https://arxiv.org/abs/2310.08560)** (Packer et al., 2023) —
OS-style virtual context with main/archival memory and explicit
paging. The canonical reference for context-as-memory-hierarchy. Right:
treats context management as a first-class problem. Doesn't help:
content is facts, not behavior; no re-injection against rules.

**[Mem0](https://arxiv.org/abs/2504.19413)** (Chhikara et al., 2025) —
production-grade fact extraction + vector/graph store. Current state of
the art on LongMemEval-style factual recall. Right: extraction is a good
model; fact store is fast and cheap. Doesn't help: retrieves facts on
demand; there is no concept of a rule being re-injected because the
agent is drifting.

**[Zep](https://getzep.com)** — temporal knowledge graph for agent
memory. Episodes decay; relations persist. Right: the temporal
decomposition is the closest existing parallel to Succession's rollup.
Doesn't help: nodes are entities and facts, not behavioral claims; no
human curation loop.

**[A-MEM](https://arxiv.org/abs/2502.12110)** (Xu et al., 2025) —
self-organizing memory that builds a Zettelkasten-style graph over
interaction traces. Right: auto-linking is a neat trick. Doesn't help:
auto-links are between memories, not between memories and a rule set.

**[LightMem](https://arxiv.org/abs/2510.18866)** — lightweight
cache-before-retrieve memory for agents. Right: low-latency retrieval.
Doesn't help: same "facts" framing.

**[MemoryBank](https://arxiv.org/abs/2305.10250)** — forgetting-curve
inspired long-term memory for conversational agents. Right: a decay
model with human-tunable pressure. Doesn't help: decay governs what is
retrievable; there is no rule-violation signal.

**[LoCoMo](https://arxiv.org/abs/2402.17753)** — benchmark for
long-context conversational memory. Measures factual recall, not
behavioral adherence. Useful as the *thing Succession is not trying to
win* — behavioral drift is orthogonal to factual recall.

**Taxonomy hit.** The 2025 memory-systems survey (arXiv:2603.07670)
catalogues the write/manage/read loop for factual memory. None of the
entries describe a system that (a) writes observations against named
rules, (b) re-injects rule text adjacent to future tool calls, (c) lets
a human curate which rules are load-bearing. That combination is not
the same problem memory systems are solving.

## Procedural memory — the closest neighbours

Procedural memory has become its own subfield since mid-2025. It is the
closest thing in the literature to behavioral inheritance.

**[ReMe](https://arxiv.org/abs/2511.15030)** — "Remember Me, Refine Me":
dynamic procedural memory framework for experience-driven agent
evolution. Explicitly distinguishes procedural memory (how to act) from
declarative/episodic (what happened). Right: the distinction matters.
Doesn't help: stays within a single agent instance; treats procedure as
another retrieval problem.

**[Mem^p](https://arxiv.org/abs/2508.06433)** (Fang et al., 2025) —
systematic procedural memory construction, retrieval, and update across
trajectories. Key finding: procedural memory from a stronger model
transfers to weaker models. This is the closest existing work to
behavioral inheritance, but it frames transfer as a retrieval problem
within single tasks rather than across session lineages.

**[MACLA](https://arxiv.org/abs/2512.18950)** (Forouzandeh et al., AAMAS
2026) — hierarchical procedural memory with Bayesian reliability
tracking. Compresses 2,851 trajectories into 187 reusable procedures
(15:1). Right: Bayesian confidence scoring is a cousin of Succession's
weight-from-observations. Doesn't help: fully automated selection, no
human shepherd, no tier hysteresis.

**[LEGOMem](https://arxiv.org/abs/2510.04851)** (Han et al., AAMAS 2026)
— modular procedural memory units allocated across multi-agent systems.
Right: the per-unit granularity echoes Succession's one-card-one-file
storage. Doesn't help: distribution is the goal; identity continuity is
not.

**[Procedural Memory Is Not All You Need](https://arxiv.org/abs/2505.03434)**
(Wheeler & Jeunen, 2025) — argues procedural memory alone is
insufficient for wicked environments; requires semantic memory and
associative learning. Useful as a theoretical grounding for why
behavioral inheritance needs multiple knowledge types, not just
procedure replay.

**Where Succession differs from procedural memory.** Procedural memory
stores traces-as-procedures for later replay. Succession stores
observations about behavior against rules — observations modify weight,
weight modifies tier, tier modifies delivery priority. The agent never
replays procedures; it reads rule text and adjusts.

## Prompt evolution — fully automated, no shepherd

**[Promptbreeder](https://arxiv.org/abs/2309.16797)** (Fernando et al.,
ICLR 2024) — evolves populations of task-prompts across generations,
also evolving the mutation-prompts themselves (self-referential).
Outperforms hand-crafted Chain-of-Thought on arithmetic benchmarks.
Right: the self-referential twist. Doesn't help: fully automated fitness
scoring; no human review; no behavioral identity — only task
performance being optimized.

**[SCOPE](https://arxiv.org/abs/2512.15374)** (Pei et al., 2025) —
context management as online optimization, dual-stream (immediate error
correction + long-term principle synthesis). The long-term principles
stream is the closest analogue to Succession's rule-text curation, but
there is no succession event, no human review, no concept of behavioral
identity passing between generations.

**Agent-Pro** (ACL 2024) — policy evolution via reflection and DFS
across belief states. Single-agent optimization; no succession.

**[MACLA](https://arxiv.org/abs/2512.18950)** — already cited under
procedural memory; also fits here. Automated Bayesian selection with no
human-in-the-loop.

**Where Succession differs.** Every other prompt evolution system is
fully automated. Succession's card text is human-edited — the LLM
proposes, the human disposes. The system is a proposal pipeline, not an
autonomous evolver.

## Rule and policy systems — front-of-context drift

**CLAUDE.md / AGENTS.md** (Anthropic + de facto convention) — a
project-root markdown file whose content is injected at the front of the
system prompt. Right: simple, works for short sessions. Doesn't help:
content drifts out of the attention window around 150k tokens on Opus;
the file is delivered once, then buried.

**[Cursor rules](https://docs.cursor.com/context/rules)** — MDC-format
rule files evaluated against file globs, injected when a matching file
is in context. Right: conditional delivery. Doesn't help: evaluation is
glob-based, not behavior-based; no observation loop.

**[Windsurf memories](https://docs.codeium.com/windsurf/memories)** —
key-value memory surfaced via retrieval. Right: fast lookup. Doesn't
help: same memory-not-rules framing.

**Cline / Aider / other coding agents** — shell command exec via tool
calls; no first-class rule delivery beyond CLAUDE.md-equivalent files.

**Where Succession differs.** Rule systems all deliver at front-of-
context (system prompt, SessionStart additionalContext, or a CLAUDE.md
prepend). Finding 1 established that this placement is near-inert on
long sessions. Succession's load-bearing channel is PostToolUse
additionalContext, which `reorderAttachmentsForAPI` bubbles adjacent to
the most recent frame.

## Behavioral science framings

**[AI Behavioral Science](https://arxiv.org/abs/2506.06366)** (2025) —
proposes studying agents as behavioral entities whose actions,
adaptations, and social patterns can be empirically measured. If agent
behavior is a first-class scientific object, behavioral inheritance
deserves first-class tooling. Succession is one attempt.

**[Dynamic Personality in LLM Agents](https://aclanthology.org/)**
(Zeng et al., ACL 2025 Findings) — personality traits evolve across
generational Prisoner's Dilemma scenarios, with measurable drift and
adaptation. Provides the closest empirical evidence that agents possess
a behavioral profile worth managing across generations.

**[Agent Behavioral Contracts](https://arxiv.org/abs/2602.22302)**
(Bhardwaj, 2026) — formal specification framework with preconditions,
invariants, governance, and recovery. Bounds drift to D* < 0.27 with
88–100% constraint compliance. Right: the behavior-as-contract framing
is a complement to Succession's tier hysteresis. Different: contracts
are authored, not observed; compliance is measured against a static
spec.

**[When Agents Disagree With Themselves](https://arxiv.org/abs/2602.11619)**
(Mehta, 2026) — behavioral consistency across 3,000 runs on Llama 70B,
GPT-4o, and Claude Sonnet 4.5. Finds 69% of behavioral divergence
occurs at step 2 of multi-step tasks, consistent runs yield 80–92%
accuracy vs 25–60% for inconsistent. Useful as a quantification of the
problem Succession is trying to dampen.

## Where Succession fits

```
                           ┌───────────────────────┐
                           │ Behavioral change     │
                           │ at attention depth    │
                           └─────────┬─────────────┘
                                     │
        fully automated              │                human shepherd
        ◄──────────────              │              ──────────────►
                                     │
    Promptbreeder / MACLA ─┐         │         ┌── CLAUDE.md / Cursor rules
    (autonomous evolution) │         │         │   (human-authored static)
                           │         │         │
                           │  ┌──────┴──────┐  │
                           │  │ Succession  │  │
                           │  │ observation │  │
                           │  │ + curation  │  │
                           │  │ + refresh   │  │
                           │  └──────┬──────┘  │
                           │         │         │
                           │         │         │
                           └─────── front-of-context ──────┘
                                     │
                                     ▼
                           ◄── adjacent-to-now ──►
                               (Finding 1)
```

The combination that doesn't exist elsewhere:

1. **Observations against named rules.** Not facts, not traces — the
   unit of evidence is "card X was confirmed/violated/invoked/consulted
   in session Y". Weight is derived from observations, not declared.
2. **Human-curated rule text.** The LLM extracts a candidate; the human
   approves or rewrites the card. No fully automated evolution.
3. **Adjacent-to-now refresh.** PostToolUse, not SessionStart. Finding
   1 showed an 18-0 split on pytest-5103 between PostToolUse and
   CLAUDE.md delivery.
4. **Tier hysteresis from observation metrics.** A card enters
   `:principle` only after sustained weight + low violation rate + real
   gap-crossings; it demotes when the exit band is triggered. No other
   system has per-rule promotion governed by measured adherence.

Succession is **not** a memory system (it doesn't recall facts), **not**
a prompt optimizer (human curates the text), and **not** a policy
engine (identity is advisory, not mechanical). It is complementary to
all three and does not replace any of them. A project can run Mem0 for
facts, Cursor rules for glob-based delivery, and Succession for
behavioral adherence without conflict.
