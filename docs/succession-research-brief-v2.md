# succession — Research Brief for Whitepaper
*New research findings only. For ALE framework details, refer to the original blog post.*

---

## 1. The Broader Research Landscape: Self-Evolving Agents

The 2025 survey *"A Survey of Self-Evolving Agents: On Path to Artificial Super Intelligence"* (Gao et al., arXiv:2507.21046) taxonomizes the field into three improvement axes:

- **Model evolution** — updating internal weights via self-generated supervision
- **Memory evolution** — accumulating and pruning knowledge across sessions
- **Prompt optimization** — refining the instructions that govern behavior without touching weights

Nearly all existing work is **fully automated** — no human in the loop. The human shepherd role in ALE is a genuine gap.

---

## 2. Closest Prior Work in Prompt Evolution

### Promptbreeder (Fernando et al., Google DeepMind, arXiv:2309.16797, ICLR 2024)
Evolves populations of task-prompts across generations. Crucially, it also evolves the *mutation-prompts* — the instructions it uses to mutate task-prompts — making it self-referential. Outperforms hand-crafted Chain-of-Thought strategies on arithmetic and commonsense benchmarks.

**Where it diverges from succession:** Fully automated selection via fitness scoring on training sets. No generational succession triggered by degradation. No behavioral identity being preserved — only task performance being optimized. The agent is a static platform being tuned, not a lineage being continued.

### SCOPE (Pei et al., arXiv:2512.15374, December 2025)
Frames context management as an online optimization problem. Synthesizes guidelines from execution traces to auto-evolve the agent's prompt via a dual-stream mechanism: one stream handles immediate error correction, the other evolves long-term principles.

**Where it diverges from succession:** Again fully automated. Operates within a single agent instance — it's continuous refinement, not generational handoff. The "long-term principles" stream is the closest thing to behavioral inheritance, but there's no succession event, no human review, and no concept of one agent's soul passing to the next.

### Agent-Pro (ACL 2024)
Evolves agent policy via reflection and depth-first search across belief states.

**Where it diverges:** Single-agent optimization. No succession, no lineage.

---

## 3. The Memory Paradigm — and Why It's Lucid Dreaming

The dominant approach to agent continuity is **memory augmentation**: storing conversational content in external databases and retrieving it at inference time. This is a booming field with a large taxonomy.

### What memory systems actually do

The field formalizes agent memory as a **write–manage–read loop** (survey: arXiv:2603.07670). The main families:

- **Context-resident compression** — summarizing or compressing conversation history to fit within the context window (MemGPT, ReadAgent, LightMem)
- **RAG-based retrieval** — chunking history into vectors and retrieving relevant chunks at query time (LoCoMo, standard RAG)
- **Structured/graph memory** — storing entities and relationships as nodes/edges in a knowledge graph (Mem0ᵍ, Zep, A-MEM, Cognee)
- **Reflective summarization** — periodic compression with LLM-generated summaries (MemoryBank uses the Ebbinghaus Forgetting Curve to weight what decays)

The state of the art: Mem0 (Chhikara et al., arXiv:2504.19413, April 2025) achieves 26% higher accuracy over OpenAI's native memory on the LOCOMO benchmark, 91% lower p95 latency, and 90% token cost reduction vs. full-context methods — by extracting "salient facts" and maintaining them in a vector+graph store.

### The lucid dreaming problem

Here's the critique: **all of these approaches are working on the content of the dream, not the dreamer.**

Memory systems ask: *"What happened that the agent should remember?"* They compress, index, and surface **facts, events, and entities** — the conversational *record*. When retrieved, these facts re-enter the context window and the agent reasons from them as if reliving the experience. The agent is still fundamentally the same stateless entity, just given a curated flashback.

This is lucid dreaming in a precise sense: the agent is navigating a managed reconstruction of past experience, aware enough to use it, but not transformed by it. The dream ends; the dreamer resets.

What memory systems **cannot** transfer across the reset boundary:

- *How* the agent learned to approach problems (strategy)
- *What patterns of failure* it fell into and should avoid (failure inheritance)
- *How it adapted its communication style* to a specific user (relational calibration)
- *Which heuristics proved reliable* vs. which sounded plausible but failed (meta-cognition)

These are **operational patterns**, not facts. They live in behavior, not content. Memory systems capture the nouns; succession captures the verbs.

### The procedural memory gap

The research itself is starting to recognise this boundary. *ReMe ("Remember Me, Refine Me", December 2025)* introduces a "dynamic procedural memory framework for experience-driven agent evolution" — explicitly distinguishing procedural memory (how to act) from declarative/episodic memory (what happened). This is the closest the memory field gets to what succession does, but it remains within a single agent instance and treats procedural memory as another retrieval problem.

The 2025 survey on memory mechanisms notes: *"many memory modules lack mechanisms for selective decay and preference-weighted recall mimicking human cognitive processes"* — and more tellingly, comprehensive benchmarks still don't test for **behavioral transfer across agent instances**. That measurement gap is itself evidence that the field isn't thinking generationally.

---

## 4. The Compression vs. Distillation Distinction

This is the crux of the contrast worth foregrounding in the whitepaper:

| | Memory Systems | succession / ALE |
|---|---|---|
| **Unit** | Facts, events, entities | Strategies, patterns, failure modes |
| **Question** | What should the agent remember? | What should the agent *become*? |
| **Mechanism** | Compress → store → retrieve | Extract → distil → imprint |
| **Boundary** | Context window management | Generational succession event |
| **Human role** | None (automated pipelines) | Shepherd — reviews, edits, authorizes |
| **Output** | Curated context injection | Successor's behavioral identity |
| **Analogy** | Dream with a better memory | Wake up as a wiser version |

Compression preserves signal from the past. Distillation transforms it into capability for the future. Memory systems are lossless-ish pipelines. Succession is a lossy-but-intentional transformation — you throw away the raw events and keep only what shapes how the next agent *acts*.

---

## 5. Emerging Signals That Point Toward This Gap

A few papers suggest the field is starting to feel the edges of what memory can do:

- **MemEvolve ("Meta-Evolution of Agent Memory Systems", December 2025)** — attempts to evolve the memory system itself, not just its contents. Closer in spirit to ALE, but still within a single agent and focused on the memory architecture rather than behavioral identity.

- **ReMe (December 2025)** — procedural memory for experience-driven evolution. Distinguishes procedural from episodic, but doesn't frame it as generational inheritance or involve human oversight.

- **AI Behavioral Science (arXiv:2506.06366, 2025)** — proposes studying agents as behavioral entities whose "actions, adaptations, and social patterns can be empirically studied" — framing that validates ALE's premise. If behavior is a first-class scientific object, then behavioral inheritance deserves first-class tooling.

- **Dynamic Personality in LLM Agents (Zeng et al., ACL 2025 Findings)** — empirically showed agent personality traits evolve across generational Prisoner's Dilemma scenarios, with measurable drift and adaptation. This is the closest empirical evidence that agents *have* a behavioral profile worth managing across generations.

None of these converge on the ALE/succession model: human-shepherded, generationally discrete, identity-preserving succession with explicit behavioral imprinting.

---

## 6. Suggested Framing for the Whitepaper

The memory field has built increasingly sophisticated plumbing to give agents **better access to their past**. That's valuable and necessary. But it doesn't address the deeper question: *who is the agent, and how does that identity develop over time?*

Memory systems extend the context window. Succession extends the agent.

The whitepaper should position ALE/succession not as competing with memory systems — agents can and should use both — but as operating at a different layer. Memory is runtime infrastructure. Behavioral inheritance is identity infrastructure. They're complementary, not substitutes.

---

## 7. Key References (New Only)

| Paper | Venue | What It Contributes |
|---|---|---|
| A Survey of Self-Evolving Agents | arXiv:2507.21046, 2025 | Taxonomy of self-evolution; shows human oversight is absent |
| Promptbreeder | arXiv:2309.16797, ICLR 2024 | Generational prompt evolution; automated, no identity |
| SCOPE | arXiv:2512.15374, Dec 2025 | Dual-stream prompt evolution from traces; no succession |
| Mem0 | arXiv:2504.19413, Apr 2025 | State-of-the-art memory; 26% accuracy gain, 90% token savings |
| Memory for Autonomous LLM Agents (survey) | arXiv:2603.07670, 2025 | Taxonomy of memory mechanisms; write–manage–read loop |
| ReMe | Dec 2025 | Procedural memory for evolution; closest memory parallel |
| MemEvolve | Dec 2025 | Meta-evolution of memory systems |
| AI Agent Behavioral Science | arXiv:2506.06366, 2025 | Scientific framing for agent behavior as empirical object |
| Dynamic Personality in LLM Agents | ACL 2025 Findings | Empirical evidence of generational behavioral drift |

---

*Compiled April 2026. Agent context for whitepaper drafting — not final copy.*
