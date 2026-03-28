# SOUL: Structured Oversight of Unified Lineage

## A Governance Framework for Persistent Agent Identity in Claude Code

**Daniel Tan** — March 2026

---

## Abstract

LLM agents suffer from two fundamental forms of memory degradation: *context poisoning*, where bad or outdated information accumulates in persistent memory and compounds errors across sessions, and *time-biased recall*, where agents disproportionately weigh recent context at the expense of earlier instructions and decisions. The Agent Lineage Evolution framework (ALE, 2025) addressed these problems for single-chat LLMs through generational succession — treating agents as temporary instantiations within an evolving lineage. SOUL extends this work into the agentic era by introducing a governance layer for Claude Code that provides persistent identity, human-authored invariants, a conscience audit loop, hierarchical knowledge inheritance, and rolling knowledge compaction. The framework requires minimal scaffolding — a `.soul/` directory and Claude Code's native hooks system — and can be adopted in any repository with a single initialization command.

---

## 1. Introduction

### 1.1 The Memory Problem

Multi-turn LLM interactions exhibit measurable performance degradation. Research demonstrates an average 39% performance drop in multi-turn versus single-turn scenarios across state-of-the-art models [4]. The "Lost in the Middle" phenomenon [3] shows performance degradation when relevant information appears in long contexts. Agents that persist across sessions face a compounding version of this problem: their persistent memory (files like MEMORY.md or CLAUDE.md) grows monotonically, stale information is never pruned, and there is no verification mechanism to distinguish confirmed knowledge from outdated assumptions.

Current approaches to this problem fall into two categories:

1. **Manual re-engineering**: Humans periodically rewrite or curate the agent's persistent memory. This scales poorly and requires the human to understand what the agent knows versus what it should know.

2. **Append-only accumulation**: The agent appends new observations to memory files without compaction or verification. This leads to context poisoning as contradictory or stale information coexists with current facts.

Neither approach addresses the fundamental tension: agents need persistent memory to be effective across sessions, but persistent memory degrades without active governance.

### 1.2 From ALE to SOUL

The Agent Lineage Evolution framework (ALE) introduced the concept of *generational succession* — when an agent degrades past measurable thresholds, it generates a structured succession package for its successor containing distilled context, failure warnings, and behavioral overrides. This eliminated the manual prompt engineering bottleneck and enabled knowledge to evolve across agent "generations."

ALE was described and demonstrated via manual prompt engineering in a blog post [1]. The concepts were validated informally through the author's use but were not packaged as reusable software. SOUL formalizes and automates ALE's principles as a distributable framework.

ALE was designed for single-chat LLM interfaces where the primary mechanism of persistence was copying text between sessions. With the emergence of agentic coding tools like Claude Code — which provide hooks, system prompt injection, custom sub-agents, and persistent project configuration — the succession model can be replaced with something more elegant: a lightweight governance layer that operates continuously rather than only at succession boundaries.

SOUL (Structured Oversight of Unified Lineage) preserves ALE's key insights while adapting them for the agentic paradigm:

| ALE Concept | SOUL Equivalent |
|---|---|
| Succession packages | Rolling compaction (continuous, not episodic) |
| Dual-process monitoring | Conscience audit loop (hook-based, not embedded) |
| Behavioral inheritance | Hierarchical genome cascade |
| Measurable triggers | Configurable audit frequency + keyword triggers |
| Human shepherd | Human-authored invariants (test-like, declarative) |

---

## 2. Architecture

SOUL is built on five pillars, each addressing a specific aspect of agent governance.

### 2.1 SOUL.md — Identity Imprinting

The central artifact is `.soul/SOUL.md`, a structured document that tells the agent *who it is*, *what it knows*, and *what mistakes its predecessors made*. It is injected into every session via a `SessionStart` hook's `additionalContext`, meaning the agent is "born" with full self-knowledge.

SOUL.md is distinct from project configuration files like CLAUDE.md:
- **CLAUDE.md** defines *project rules* — coding style, tool preferences, repository conventions. It is about the *work*.
- **SOUL.md** defines *agent identity* — role understanding, accumulated knowledge, behavioral patterns, failure warnings. It is about the *worker*.

The document has five sections:

- **Identity**: The agent's purpose and role in this repository.
- **Accumulated Knowledge**: Facts, patterns, and decisions confirmed across sessions.
- **Predecessor Warnings**: Explicit failure modes from previous agent generations — things the agent must not repeat.
- **Current Understanding**: A compact summary of the codebase and task state as of the last compaction.
- **Skills**: Declarations of specialized skill roles (see Section 2.5).

### 2.2 Human-Led Invariants

The most critical design decision in SOUL is that *humans define the rules, and the system enforces them*. Invariants are test-like assertions stored in `.soul/invariants/*.md` that the conscience checks against. They are:

- **Human-authored**: Only the human creates and modifies invariants.
- **Immutable to the agent**: The agent cannot edit invariant files.
- **Categorical**: Architectural invariants (structural rules), behavioral invariants (process rules), and knowledge invariants (factual constraints).

This design mirrors the relationship between tests and code: the human defines what correctness means, and the system continuously verifies it. Unlike ALE's embedded monitoring (Process B), invariants are external to the agent and survive agent death — they are properties of the *project*, not the *agent*.

Examples of invariants:
```
- Never introduce circular dependencies between modules
- Always read a file before editing it
- The auth system uses JWT, not sessions — never suggest sessions
```

### 2.3 Conscience — The Audit Loop

The conscience is a `Stop` hook that runs after every agent response, implementing a tiered audit strategy:

**Lightweight turns** (most responses): The hook increments a turn counter and exits. No cost is incurred.

**Keyword-triggered audits**: If the agent's response contains significant keywords (commit, delete, deploy, push, force), a full audit runs regardless of the turn counter.

**Scheduled audits** (every N turns, configurable): The hook reads all invariants, sends the agent's last response to a lightweight model (Haiku by default) via `claude -p`, and asks: "Does this response violate any of these invariants?"

**Violation handling** is tiered:
1. **First violation**: The hook returns `{"decision": "block", "reason": "CONSCIENCE: ..."}`, which forces the agent to acknowledge the violation and correct its behavior before continuing.
2. **Repeated violations** (configurable threshold): After N violations on the same invariant, the hook signals session termination, forcing a fresh restart that re-imprints the soul.

This design borrows from ALE's succession triggers (measurable degradation → forced reset) but makes it continuous rather than episodic, and external rather than self-assessed.

**Cost control**: Because the conscience uses a small, fast model (Haiku) and only runs full audits on a configurable fraction of turns, the overhead is controlled and configurable. With default settings (audit every 5 turns, Haiku model), the conscience adds one Haiku API call per 5 agent turns plus triggered audits on safety-critical keywords. Cost can be tracked via the audit trail in `.soul/log/conscience.jsonl`.

### 2.4 Hierarchical Souls — Genome Inheritance

Knowledge that is universal across repositories should not be duplicated in every `.soul/SOUL.md`. SOUL introduces a genome cascade inspired by CSS specificity:

```
~/.soul/genome/base.md            ← universal agent traits
~/.soul/genome/typescript.md      ← language-specific knowledge
~/.soul/genome/backend-api.md     ← project-archetype knowledge
.soul/SOUL.md                     ← repo-specific soul (most specific)
```

The `SessionStart` hook assembles the full soul by concatenating genome fragments (in configured order) with the repo-level soul. When fragments conflict, **the more specific level wins** — repo soul overrides archetype, archetype overrides language, language overrides base.

This mirrors how developers organize dotfiles (global git config → repo git config) and enables knowledge sharing across an organization's repositories. A team can maintain shared genome fragments that encode architectural patterns, preferred libraries, and common pitfalls, while each repository's soul captures its specific context.

### 2.5 Soul-Derived Skills

The `## Skills` section of SOUL.md declares specialized skill roles that are generated as Claude Code skills (`.claude/skills/<name>/SKILL.md`) at session start:

```markdown
### reviewer
Reviews code changes against architectural invariants and accumulated knowledge.
Focus: correctness, invariant compliance, pattern consistency.
mode: fork
```

Each skill declaration is converted into a Claude Code skill file with YAML frontmatter. Skills are **lazy-loaded** — only activated when invoked via `/skill-name` or auto-invoked when the description matches. Each generated skill uses `` !`cat` `` preprocessing to dynamically inject the current SOUL.md and invariants at invocation time, meaning skills always see the latest soul state without regeneration.

Skills may optionally declare `mode: fork` to run as isolated subagents (`context: fork` + `agent: Explore` in frontmatter). Skills without `mode: fork` run inline in the main conversation.

Generated skill files carry a `# SOUL-MANAGED` header and are always overwritten at session start. The single source of truth is the `## Skills` section in SOUL.md.

---

## 3. Rolling Compaction

### 3.1 The Problem with Append-Only Memory

Traditional agent memory (MEMORY.md, project notes files) grows monotonically. Every session appends new observations, but nothing is ever removed, verified, or deduplicated. Over time, this creates several failure modes:

- **Contradictions**: Old observations coexist with newer corrections.
- **Staleness**: Information about deleted files, resolved bugs, or changed APIs persists.
- **Noise**: Session-specific observations (temporary debugging notes, intermediate steps) crowd out durable knowledge.
- **Size**: The memory file exceeds useful context length, triggering the "Lost in the Middle" problem.

### 3.2 Compaction as an LSM-Tree

SOUL treats knowledge like a Log-Structured Merge-tree (LSM-tree):

1. **Write**: During a session, the agent accumulates knowledge in its working context.
2. **Flush**: When Claude Code compacts the context window (the `PostCompact` event), the hook captures the session's transcript.
3. **Merge**: A `claude -p` call reads the transcript alongside the current SOUL.md and produces an *updated* SOUL.md that:
   - Merges new knowledge into existing sections
   - Resolves contradictions (newer information wins, with the contradiction noted)
   - Prunes stale or redundant information
   - Promotes patterns confirmed across multiple sessions
4. **Commit**: The updated SOUL.md is written and optionally auto-committed to git.

The result is a soul that is *intended to improve* over time rather than merely growing. Whether compaction consistently achieves this is an empirical question addressed in Section 7.5 and the accompanying experiments. Each compaction cycle is a chance to refine, correct, and compress the agent's self-knowledge.

### 3.3 Safety Mechanisms

Compaction is not infallible. The framework includes several safeguards:

- **Structural validation**: The compacted output must contain all expected sections (Identity, Accumulated Knowledge, etc.) or the update is rejected.
- **Minimum size check**: If the compacted output is suspiciously short (< 50 characters), it is discarded.
- **Git history**: When `autoCommit` is enabled, every compaction is a git commit, providing full rollback capability.
- **Logging**: Every compaction event (including failures) is logged to `.soul/log/conscience.jsonl` with before/after size metrics.

---

## 4. Implementation

### 4.1 File Structure

```
~/.soul/                               # Global (user-level)
├── genome/
│   └── base.md                        # Universal agent traits

.soul/                                 # Per-repo
├── SOUL.md                            # Repo soul
├── invariants/
│   ├── architecture.md                # Structural invariants
│   ├── behavior.md                    # Process invariants
│   └── knowledge.md                   # Factual invariants
├── config.json                        # Conscience & compaction settings
├── hooks/
│   ├── session-start.sh               # Genome assembly + context injection + skill generation
│   ├── conscience.sh                  # Tiered audit loop
│   └── compact.sh                     # Rolling compaction
└── log/
    └── conscience.jsonl               # Audit trail

.claude/
├── settings.json                      # Hook registrations
└── skills/                            # Soul-generated skills (SOUL-MANAGED)
    ├── reviewer/SKILL.md
    ├── refactorer/SKILL.md
    └── explorer/SKILL.md
```

### 4.2 Hook Integration

SOUL uses three Claude Code hooks:

| Hook Event | Script | Purpose |
|---|---|---|
| `SessionStart` | `session-start.sh` | Assemble genome cascade, inject as `additionalContext`, generate skill files |
| `Stop` | `conscience.sh` | Tiered conscience audit, block violations |
| `PostCompact` | `compact.sh` | Rolling compaction of SOUL.md |

Hooks are registered in `.claude/settings.json` and activate automatically for any `claude` session in the repository. No alias, wrapper, or launcher is needed.

### 4.3 Context-Aware Status Line

SOUL includes a status line script that displays real-time context usage alongside SOUL memory size. Claude Code's status line API provides `context_window.used_percentage` on every update, enabling a color-coded compaction indicator:

- **Green** (below threshold): Context is in the high-quality zone
- **Yellow** (at threshold, default 15%): "consider /compact" — context approaching the quality degradation point
- **Red** (threshold + 5%): "/compact recommended" — quality degradation likely in progress

The threshold is configurable via `.soul/config.json` (`compaction.suggestAtPercent`). The default of 15% corresponds to approximately 150k tokens on a 1M-token context model — the empirically observed threshold at which instruction following begins to degrade in interactive sessions.

This addresses a fundamental timing problem: Claude Code's built-in auto-compaction triggers when the context window fills up, which may be well past the point where output quality has already degraded. SOUL's status line provides early warning, allowing the user to proactively compact before quality suffers.

### 4.4 Installation

SOUL can be installed via two methods:

**Method A: skills.sh (recommended)**

```bash
npx skills add danieltanfh95/agent-lineage-evolution --skill soul
```

This installs SOUL as a Claude Code skill with hooks declared in the skill's YAML frontmatter. The hooks activate automatically — no manual `settings.json` editing required. The user then runs `/soul setup` for interactive configuration.

**Method B: Manual initialization**

```bash
./soul-init.sh
```

This creates the directory structure, installs hook scripts, generates template files, and merges hook registrations into `.claude/settings.json`. The human then fills in the Identity section of SOUL.md and authors the initial invariants.

---

## 5. Design Decisions and Tradeoffs

### 5.1 External Conscience vs. Self-Assessment

ALE used embedded self-monitoring (Process B) where the agent assessed its own cognitive state. This had a fundamental limitation: a degraded agent is a poor judge of its own degradation. Research on LLM self-correction confirms that LLMs generally cannot reliably correct their own mistakes without external feedback [5].

SOUL externalizes the conscience to a separate model invocation (`claude -p`). This provides independence — the conscience is not subject to the working agent's context poisoning — at the cost of API calls. The cost is controlled through tiered frequency (most turns are free, audits run every N turns or on keyword triggers) and model selection (Haiku by default).

### 5.2 Invariants as Tests

The decision to make invariants human-authored and agent-immutable is deliberate. If agents could modify their own invariants, the governance mechanism would be vulnerable to the same degradation it aims to prevent. This mirrors the software engineering principle that *tests should be independent of the code they verify*.

The tradeoff is that invariants require human maintenance. But this is precisely the right division of labor: humans define *what* the agent should do (declarative invariants), and the conscience verifies *that* the agent does it (automated enforcement).

### 5.3 Genome Cascade vs. Monolithic Soul

The hierarchical genome system adds complexity but enables knowledge reuse across repositories. A developer maintaining multiple projects can encode their universal preferences once in `~/.soul/genome/base.md` rather than duplicating them in every repo's SOUL.md.

The conflict resolution rule (most specific wins) is simple and predictable — borrowed from CSS specificity, git config precedence, and other familiar cascade systems.

### 5.4 Rolling Compaction vs. Append-Only

Rolling compaction is the highest-risk component of the framework. A flawed compaction could lose important knowledge. The safety mechanisms (structural validation, size checks, git history, logging) mitigate this, but the human should periodically review SOUL.md after compaction cycles to verify quality.

Over time, compaction quality can be evaluated empirically by comparing the conscience violation rate before and after compaction cycles — the hypothesis is that violation rates should decrease. This is tested in the accompanying experiment suite (see `experiments/`).

### 5.5 Compaction Model Selection

The choice of model for compaction has a dramatic effect on quality. Early benchmarking against LongMemEval [9] — a multi-session memory benchmark with ~50 conversation sessions per instance — revealed that **model capability is the primary determinant of compaction quality**, more important than prompt engineering or pipeline architecture.

| Model | Memory after 50 sessions | Behavior |
|-------|-------------------------|----------|
| Haiku 4.5 | ~28,000 chars (growing) | Copies content instead of compressing — effectively append-only |
| Haiku 4.5 (two-stage extract→merge) | ~15,000 chars (growing) | Better than raw Haiku but still fails to compress sufficiently |
| Sonnet 4.6 | ~5,800 chars (stable) | Reliable compression with good detail retention |
| Opus 4.6 | ~5,800 chars (stable) | Best compression and detail retention |

Haiku's failure is instructive: despite explicit instructions to "keep under 2000 tokens," it cannot reason about what to discard when given a large input. It defaults to preserving everything, causing unbounded growth. Sonnet 4.6's extended thinking capability appears to be the key differentiator — the model can plan its compression strategy before generating output, achieving near-Opus quality at lower cost.

Based on these findings, the framework's default compaction model is Sonnet 4.6. Haiku remains the default for conscience audits, where the task (checking invariants against a response) is simpler and does not require the same compression reasoning.

### 5.6 Proactive Compaction Timing

A key observation motivates SOUL's approach to compaction timing: LLM output quality degrades significantly before the context window is full. In interactive Claude Code sessions, instruction drift — where the agent stops following process instructions such as "document before implementing" — is observed around 150k tokens, well below the 1M token context limit.

Claude Code's built-in auto-compaction triggers only when the context window approaches its limit. By that point, the agent may have been operating in a degraded state for hundreds of thousands of tokens. SOUL addresses this with a configurable status line indicator (Section 4.3) that suggests `/compact` at a user-defined threshold — defaulting to 15% of the context window, approximately 150k tokens on current models.

This is a suggestion, not an automated trigger — the user retains control over when compaction occurs. The status line provides the awareness that compaction would be beneficial; the decision to act remains human.

---

## 6. Relationship to Prior Work

### 6.1 Agent Lineage Evolution (2025)

SOUL is a direct successor to ALE. The key conceptual shift is from *episodic succession* (agent dies, knowledge transfers to successor) to *continuous governance* (agent is continuously monitored and its knowledge continuously refined). This is enabled by Claude Code's hooks system, which provides the event-driven integration points that single-chat interfaces lacked.

### 6.2 MEMORY.md / Auto-Memory

Claude Code's built-in auto-memory system stores observations in `MEMORY.md` and injects them into subsequent sessions. SOUL complements rather than replaces this: MEMORY.md captures session-specific observations, while SOUL.md captures durable identity and knowledge. Rolling compaction can draw from both sources.

### 6.3 CLAUDE.md

CLAUDE.md serves as project configuration — coding standards, tool preferences, repository conventions. It answers "how should work be done in this project?" SOUL.md answers "who is the worker, what does it know, and what mistakes should it avoid?" The two are complementary and occupy different conceptual spaces.

---

## 7. Future Work

### 7.1 Skill Specialization

Skills are now generated as Claude Code skill files with full YAML frontmatter, enabling lazy loading and first-class `/skill-name` invocation. Future work could explore richer skill orchestration — for example, skills that automatically invoke other skills, or skills with tool-use restrictions for tighter isolation.

### 7.2 Cross-Repo Genome Evolution

When a pattern proves universal across multiple repo souls, it could be automatically promoted to a global genome fragment. This requires a mechanism to detect convergent knowledge across repositories — potentially a periodic `claude -p` call that compares multiple SOUL.md files and proposes genome promotions.

### 7.3 Team Souls

In a team setting, genome fragments could be distributed via a shared repository (analogous to shared dotfiles or ESLint configs). This would enable organizational knowledge to propagate to all team members' agents automatically.

### 7.4 Conscience Cost Optimization

The current conscience uses a fixed model (configurable) for all audits. A future version could use a two-pass system: a regex/keyword pre-filter (zero cost) followed by an LLM audit only when the pre-filter flags a potential issue. This could substantially reduce API costs while maintaining coverage.

### 7.5 Empirical Validation

The framework needs empirical validation across diverse use cases:
- Does rolling compaction actually reduce context poisoning over time?
- What is the optimal audit frequency (cost vs. violation catch rate)?
- Do genome cascades reduce setup time for new repositories?
- How do conscience violation rates correlate with agent output quality?

Six experiments addressing these questions are included in the `experiments/` directory:

1. **Compaction vs. Append-Only** (`experiments/01-compaction-vs-appendonly/`): Measures whether rolling compaction reduces contradiction and staleness compared to append-only memory across 10 sessions with predetermined codebase changes.
2. **Conscience Catch Rate** (`experiments/02-conscience-catch-rate/`): Evaluates the conscience's precision, recall, and F1 score against 20 synthetic agent responses (10 violating, 10 clean).
3. **Genome Cascade Setup Time** (`experiments/03-genome-cascade-setup-time/`): Tests whether pre-populated genomes reduce the number of turns needed to complete standardized tasks in new repositories.
4. **Compaction Quality Trajectory** (`experiments/04-compaction-quality-trajectory/`): Tracks SOUL.md quality metrics (size, completeness, accuracy, contradictions, information density) across 15 compaction cycles to determine whether quality improves over time.
5. **SOUL vs. LongMemEval** (`experiments/05-longmemeval/`): Benchmarks SOUL's compaction-based memory against LongMemEval [9], an ICLR 2025 benchmark testing chat assistants' long-term memory across multi-session conversations (500 questions, ~50 sessions per instance, ~500k chars). Preliminary results (5 instances, Information Extraction category) show Sonnet 4.6 compaction achieving 2-3/5 correct answers while compressing ~500k chars of conversation into ~5,800 chars of memory — a ~86x compression ratio. The experiment also revealed that Haiku 4.5 is unsuitable for compaction (memory grows without bound), leading to the model selection findings in Section 5.5.
6. **SOUL-Bench** (`experiments/06-soul-bench/`): A purpose-built eval testing SOUL's core competencies on code project memory. Uses a synthetic 10-session Express.js API development scenario with 20 questions across 5 categories: fact retention, contradiction resolution, staleness detection, abstention, and predecessor warnings. Initial results show SOUL with Sonnet 4.6 compaction scoring 20/20 (100%) while compressing 10 sessions into ~5.1k chars of structured SOUL.md — compared to 6/20 (30%) for a no-memory baseline. Note: append-only memory (no compaction) also scored 20/20 at this scale (~10k chars of accumulated text), indicating that SOUL's compression advantage becomes meaningful only at larger scales where append-only memory exceeds practical context limits. Layer 2 of the eval (planned) will test whether proactive compaction prevents the instruction drift observed at high context depths (>150k tokens).

---

## 8. Conclusion

SOUL provides a minimal but complete governance layer for Claude Code agents. By externalizing conscience (the audit loop), persistence (rolling compaction), identity (SOUL.md), and rules (invariants) into a `.soul/` directory structure integrated via native hooks, the framework addresses the fundamental memory degradation problems that limit long-running agent effectiveness.

The key insight — inherited from ALE and refined for the agentic era — is that agent knowledge should be treated as a living artifact that is continuously compacted, verified against human-defined invariants, and composed from hierarchical sources. Agents are not permanent; their knowledge should be.

---

## References

1. Tan, D. (2025). *Agent Lineage Evolution: A Novel Framework for Managing LLM Agent Degradation*. https://danieltan.weblog.lol/2025/06/agent-lineage-evolution-a-novel-framework-for-managing-llm-agent-degradation
2. Anthropic. (2026). *Claude Code Hooks Documentation*. https://code.claude.com/docs/en/hooks
3. Liu, N. F., et al. (2023). *Lost in the Middle: How Language Models Use Long Contexts*. arXiv:2307.03172.
4. Laban, P., Hayashi, H., Zhou, Y., & Neville, J. (2025). *LLMs Get Lost In Multi-Turn Conversation*. arXiv:2505.06120.
5. Kamoi, R., et al. (2024). *When Can LLMs Actually Correct Their Own Mistakes? A Critical Survey of Self-Correction of LLMs*. arXiv:2406.01297.
6. Chen, Y., et al. (2025). *Code Copycat Conundrum: Demystifying Repetition in LLM-based Code Generation*. arXiv:2504.12608.
7. Kosmyna, N., & Hauptmann, E. (2025). *Brain on LLM*. https://www.brainonllm.com. (Preprint)
8. Pan, L., et al. (2023). *Automatically Correcting Large Language Models: Surveying the Landscape of Diverse Automated Correction Strategies*. arXiv:2308.03188.
9. Wu, D., et al. (2025). *LongMemEval: Benchmarking Chat Assistants on Long-Term Interactive Memory*. ICLR 2025. arXiv:2410.10813.
