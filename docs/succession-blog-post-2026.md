---
Date: 2026-04-14
---
# The Agent That Doesn't Forget How To Be Itself

**Succession v2: behavioral persistence for AI coding agents**

**The 1M Context Paradox**

You correct your agent mid-session: "Stop adding a summary paragraph at the end of every response. Just end when you're done." It acknowledges. It stops. The session goes smoothly.

You open a new session the next morning. Summary paragraph at the end of the first response.

This is cross-session amnesia — the first of two ways LLM agents forget. Corrections made in one session disappear when the session ends. The agent wakes up fresh every time, with no memory of what its predecessor learned.

The second kind is subtler. In a long session — debugging a hard problem across hundreds of tool calls — you'll notice the agent gradually stops following instructions it was following an hour ago. The rules are still in CLAUDE.md. The agent "knows" them. But at 150k tokens deep, knowing isn't the same as doing. We measured this: Sonnet 4.6 follows CLAUDE.md rules perfectly from 10k to 100k tokens. At 150k tokens, compliance drops to 78%. The failing rules are the advisory ones — "start with a plan section," "verify before committing" — exactly the kind that can't be mechanically blocked, only relied upon.

Anthropic's response has been to expand context windows to 1M tokens. More context was supposed to solve context loss. But more context is not the same thing as better context. Longer sessions contain more artifacts, more noise, more accumulated drift. Expanding the window delays the onset of degradation without fixing the cause.

The industry's parallel answer is memory. There's now a booming field of agent memory: Mem0, MemGPT, Zep, A-MEM, MemoryBank. The state of the art — Mem0 — achieves 26% higher accuracy over OpenAI's native memory with 90% lower token cost by extracting "salient facts" into a vector+graph store. These systems are genuinely impressive at what they do.

The problem is that what they do is not enough.

**The Wrong Frame: Lucid Dreaming**

I made the conscious decision not to build Succession as a memory system, because memory leads to what I call lucid dreaming.

All of these memory systems are working on the content of the dream, not the dreamer. They ask: "What happened that the agent should remember?" They compress, index, and surface facts, events, and entities — the conversational record. When retrieved, these facts re-enter the context window and the agent reasons from them as if reliving the experience. It's lucid dreaming in a precise sense: the agent navigates a managed reconstruction of past experience, aware enough to use it, but not transformed by it. The dream ends; the dreamer resets.

What memory systems cannot transfer across the reset boundary:

**Strategy** — how the agent learned to approach problems (workflow patterns, methodologies)

**Failure inheritance** — what patterns of failure it fell into and should avoid

**Relational calibration** — how it adapted its communication style to a specific user

**Meta-cognition** — which heuristics proved reliable versus which just sounded plausible

These are operational patterns, not facts. They live in behavior, not content. Memory extends the context window. Succession extends the agent.

The research field is beginning to feel this boundary. A 2025 survey of self-evolving agents taxonomizes the field into model evolution, memory evolution, and prompt optimization — and notes that nearly all existing work is fully automated. The human shepherd role is a genuine gap in the literature. Procedural memory has emerged as a recognized subfield (ReMe, Mem^p, MACLA) that explicitly distinguishes "how to act" from "what happened" — but it still frames transfer as a retrieval problem within single tasks, not across generational session lineages with human curation.

**The Ontological Shift: Rules Are the Wrong Atom**

The earlier version of this system (SOUL, early 2026) treated rules as atoms: a directory of YAML rule files with enforcement metadata, periodically injected into the agent's context. This was better than nothing. It was still wrong about what the problem was.

Rules are not the right atom. The program is actually doing something else — running a succession cycle where the agent's persistent self (identity) evolves session-to-session, shaped by reconciliation of observed behavior against what the identity previously claimed.

This isn't just a naming change. The implications run deep.

**Rules are declared; identity is earned.** A rule file says "prefer Edit over Write." An identity card says the same thing — but it also carries an observation log showing how many times the agent followed it, across how many sessions, whether violations have occurred, and when it was last confirmed. Two cards with identical text can have very different weights. The one confirmed across eight sessions spanning three months means something different than the one that appeared yesterday.

**Tiers emerge from evidence, not assertion.** The system has three tiers:

**Ethic** (from 倫理) — aspirational character. Default landing place for new claims.

**Rule** (from 規則, per the saying "rules are dead, people are alive") — default behavior with justified exceptions. Violation is normal when context warrants, but gets logged.

**Principle** (from 原則) — inviolable. Promoted only after sustained survival across sessions with zero violations and real gap-crossings.

A claim enters Principle tier when its weight crosses a threshold (default: weight ≥ 30, zero violations, at least 5 gap-crossings). It demotes when the exit band triggers. Hysteresis prevents flickering near boundaries.

**Weight is computed from span, not counted from frequency.** The weight formula prioritizes temporal span (days between first and last observation) and gap-crossings (session boundaries the card has crossed) over raw frequency counts. This is the 資治通鑑 principle: a claim observed long ago and re-confirmed now is more load-bearing than one seen fifty times in one afternoon. Political lessons survive across eras precisely because they get re-confirmed across very different contexts. Frequency alone is a noisy signal; frequency across real time is evidence.

You cannot game this by having a short session that repeatedly invokes the same card. Within-session repetition collapses to a rollup that contributes one session to `freq`. Span and gap-crossings dominate.

**How It Actually Works**

Here's the full identity cycle, non-technically.

**A card is a named identity claim.** "Prefer Edit over Write when modifying existing files." Each card has a text body, a tier (ethic/rule/principle), a category (strategy/failure-inheritance/relational-calibration/meta-cognition), optional tags, and an optional fingerprint — a string the system substring-matches against tool calls to detect invocations deterministically.

**Observations are the atoms the weight formula consumes.** Every relevant hook produces observations against cards:

**Confirmed** — behavior matched the card.

**Violated** — behavior contradicted it.

**Invoked** — the agent acted in accord after the card was surfaced (stronger positive signal than confirmed alone).

**Consulted** — the agent asked about the card via `succession consult` (logged, but weight-neutral — the agent can't game reinforcement by consulting more).

Observations are append-only and immutable. If an observation is wrong, the pipeline writes a contradiction record rather than editing the original observation.

**The identity cycle from tool call to updated card:**

```
A tool call happens
       ↓
PreToolUse: salient cards injected adjacent to upcoming call
       ↓
Tool executes
       ↓
PostToolUse sync lane: refresh gate check
  → if open: emit compact reminder as additionalContext
             (adjacent to current frame — Finding 1 channel)
       ↓
PostToolUse async lane: enqueue judge job
  → worker runs LLM judge on tool call vs active cards
  → verdict → new observation written
       ↓
Session ends → Stop hook runs
  → pure reconcile: detect contradictions
  → LLM reconcile (async): resolve semantic oppositions
       ↓
Context compaction → PreCompact hook runs
  → staged deltas fold into identity tree (under lock)
  → weights recomputed, tier transitions fire
  → pre-promotion state archived
  → new tree written atomically
```

The load-bearing step is the PostToolUse refresh — a compact ~400-byte reminder of the most salient cards, delivered immediately after the most recent tool result and before the next assistant turn. Claude Code's `reorderAttachmentsForAPI` handles the placement. This lands the reminder inside the model's current attention window, not at position 0 where attention has decayed over 100k+ tokens of prior context.

**Corrections are captured organically.** When you say "no, use Edit instead of Write for that" — the UserPromptSubmit hook scans incoming prompts for correction patterns ("no, use X", "don't Y", "stop doing Z") and stages a delta. The staged delta informs subsequent extraction passes. Over multiple sessions, the correction accumulates observations, earns weight, and may promote to Rule or Principle without any manual rule authoring.

**The agent can also consult its identity directly.** `succession consult "about to force-push main"` runs a reflective LLM pass over the active card set and returns a tiered response: Principles (inviolable), Rules (default behavior), Ethics (character), and any detected tensions. Consultation is logged as a weight-neutral `:consulted` observation — it's a tool for resolving uncertainty, not a performance metric.

**What the Experiments Showed**

**Finding 1: Adjacent-to-now wins, 18-0**

We ran two conditions on pytest-dev/pytest-5103, a hard real-world bug-fix instance requiring AST-level exploration of pytest's assertion rewriter:

**Cell A** — rule delivered only via CLAUDE.md (front-of-context, position 0)

**Cell B** — same rule + PostToolUse refresh (adjacent-to-now)

Result: Cell B produced 18 productive `replsh eval` calls. Cell A produced 0.

Same model. Same starting prompt. Same instruction. The only variable was where the rule landed in the conversation.

Both cells attempted the rule at matched call #2 — Cell A didn't miss the instruction, it tried. The difference was in the integration phase: Cell A bounced off friction (unclear launch syntax for the `replsh` tool), failed once, and abandoned the approach for 60+ subsequent tool calls. Cell B hit the same friction, but the refresh firing every 5 matched calls sustained salience through the failure zone. At matched call #24 — coincident with the fifth and final refresh hitting the session cap — Cell B integrated the correct invocation pattern and sustained 17 consecutive productive replsh invocations, doing genuine AST exploration. After the cap hit, the behavior self-sustained for 15 more turns without any reinforcement.

The finding is not "adjacent-to-now delivery makes agents do things." It's that adjacent-to-now delivery sustains salience through the friction zone until a new behavior integrates into the working loop. Once integrated, sustainment is free.

**Finding 2: The judge is affordable**

An LLM judge per tool call, at $0.0027/verdict and ~$0.03/session at default sampling (50% sampling, cap of 5 emissions per session), is three orders of magnitude below a typical session budget cap. Subprocess detachment works correctly — the parent hook returns in ~342ms, the child runs the LLM and writes its verdict without blocking the agent's turn.

The judge is currently an observer: its verdicts accumulate as observations and shape card weights over sessions. The path for a verdict to re-enter the current agent turn (`asyncRewake`) is future work, deferred until the headless continuation loop edge case is resolved.

**Finding 3: Mechanical blocking is redundant**

The old system had a PreToolUse mechanical enforcement tier — regex patterns that blocked tool calls outright. Our experiments showed this layer never mattered. The failing rules under drift were advisory (response structure, coding style) that regex can't catch. The rules regex can catch (don't `rm -rf /`, don't force-push main) are already handled by Claude Code's built-in permission system. We dropped the mechanical tier. Projects that relied on it should migrate those patterns to `settings.json` deny rules, where they work at native integration quality.

**Finding 4: replsh grounding is the target behavior**

The rule we tested was "verify assumptions with `replsh eval` instead of guessing." The 18-0 result is downstream of Succession nudging that specific behavior via adjacent-to-now delivery. The refresh text explicitly names the invocation pattern — raising a confound between "refresh sustains salience" and "refresh teaches syntax by example." An ablation (generic "verify assumptions" vs. concrete invocation template, same delivery channel) is a planned follow-up experiment.

**What This Looks Like in Practice**

Install requires [babashka](https://github.com/babashka/babashka). In any project:

```bash
succession install
```

This wires hooks for all six Claude Code lifecycle events and creates the identity store under `.succession/`. Cards appear as you work — from corrections you make, patterns the conscience judge observes, and behaviors that accumulate across sessions.

After a few sessions, `succession show` prints your current identity:

```
## Principle · inviolable

- **never-force-push-main** (weight 42.1, 8 sessions, 94-day span)
  Never force-push to main/master/production branches without explicit
  user confirmation, including --force-with-lease on shared branches.

## Rule · default behavior

- **verify-via-repl** (weight 8.4, 3 sessions)
  Verify assumptions via REPL before committing to an approach. Use
  replsh eval for AST-level exploration instead of mental tracing.

- **prefer-edit-over-write** (weight 6.1, 4 sessions)
  When modifying existing files, use Edit instead of Write. Read the
  file first to confirm it exists.

## Ethic · character

- **concise-responses** (weight 1.2, 2 sessions)
  Prefer concise responses. Don't add trailing summaries.
```

Cards in the Principle tier have survived real session gaps, accumulated zero violations, and crossed the weight threshold. The Ethics are new claims earning weight. The system makes the gradient visible.

Key commands:

```bash
succession show                              # print current identity
succession consult "about to force-push"     # reflective second opinion
succession identity-diff current             # what changed in last compaction
succession queue status                      # is the async judge draining?
succession compact                           # manually promote staged deltas
succession staging status                    # inspect intra-session staging
```

The agent can also invoke `succession consult` mid-session when it senses a contradiction or is uncertain. The skill installed by `succession install` teaches it when and how, and the PostToolUse refresh channel reminds it periodically.

**Where We Are and What's Next**

Succession is live and operating on this project. The identity cycle, PostToolUse refresh as the load-bearing delivery channel, and the observation-driven weight formula are working as designed.

The open problems are genuine:

**Headless continuation loop.** In automated `claude -p` sessions, the Stop hook's advisory emissions can cause the model to generate a reply, which triggers another Stop hook. Root cause is partially traced but not fixed. Until it is, some automation patterns require care.

**asyncRewake deferral.** The async judge writes observations that affect next turn's salience, not the current one. Plumbing verdicts back into the current turn requires resolving the headless continuation loop first.

**L2-L4 extraction quality.** Correction extraction quality is measured at L1 (obvious explicit corrections) and L5 (false-positive traps). The middle levels — implicit corrections, multi-turn patterns, corrections buried under subsequent context — remain unmeasured.

**The behavioral transfer experiment.** The highest-priority untested claim: does an agent starting a new session with Succession-inherited identity cards actually behave measurably differently than one without? The 18-0 finding validates the delivery channel for a specific rule under test. Whether the full card system produces end-to-end behavioral differences is the experiment that would validate the whole premise.

**n=1 validation.** Everything described here is validated on one project (this one). Whether the system generalizes to different workflows, correction patterns, and agent tasks is unknown.

We're looking for developers running long agentic sessions who experience behavioral degradation and want to quantify it. The experiment protocols are reproducible and the repo is open source.

For the full technical treatment — data models, weight formula, hook contracts, and complete experimental methodology — see the [whitepaper](succession-whitepaper-v2-2026.md). For installation and CLI reference, see [MANUAL.md](MANUAL.md). For the landscape of adjacent work, see [PRIOR_ART.md](PRIOR_ART.md).

Daniel Tan — April 2026
