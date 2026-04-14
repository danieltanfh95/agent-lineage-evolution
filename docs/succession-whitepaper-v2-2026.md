# Succession: An Identity Cycle for Behavioral Persistence in LLM Coding Agents

**Daniel Tan**

April 2026

**Abstract**

LLM-based coding agents suffer from two forms of behavioral amnesia that memory augmentation cannot address. Cross-session amnesia erases corrections and preferences when sessions end. Intra-session drift degrades instruction adherence as context windows fill — empirically, Sonnet 4.6 compliance with advisory rules drops from 100% at 10k tokens to 78% at 150k. We present Succession, a system that addresses both problems through an identity cycle: an append-only observation log over named behavioral claims (cards), a span-weighted authority formula that rewards temporal persistence over frequency, and a tier system (Ethic/Rule/Principle) governed by hysteresis bands on those weights. The load-bearing delivery channel is PostToolUse `additionalContext`, which Claude Code's attachment reorderer places adjacent to the current frame rather than at position 0 where attention has decayed.

We report four key findings from the conscience-loop experiment: (1) adjacent-to-now PostToolUse refresh produced 18 productive tool uses where front-of-context CLAUDE.md delivery produced 0, on the same model with the same starting instruction; (2) an LLM judge running per tool call at ~$0.03/session is affordable but currently observer-only; (3) the previous mechanical PreToolUse enforcement tier is redundant with Claude Code's permission system and has been removed; (4) `replsh`-grounded verification is the target behavior Succession nudges through the refresh channel. Open problems include the headless continuation loop in automated sessions, asyncRewake deferral preventing in-turn feedback, L2-L4 correction extraction quality gaps, and the behavioral transfer experiment — the highest-priority untested claim.

The framework is open source at https://github.com/danieltanfh95/agent-lineage-evolution.

**1. Introduction**

**1.1 The Two Amnesia Problem**

LLM-based agents operating in interactive coding environments exhibit two measurable forms of behavioral degradation that persist regardless of context window size.

**Cross-session amnesia.** When an agent session ends, all in-context state is lost. Corrections, preferences, and operational patterns negotiated during a session — "use Edit instead of Write," "don't add trailing summaries," "verify assumptions before proposing a solution" — are not transferred to the next session. Users repeat corrections indefinitely. The agent that starts a new session has no recollection of what its predecessor learned.

**Intra-session drift.** Rules injected at session start (via system prompts, CLAUDE.md files, or SessionStart `additionalContext`) lose behavioral influence as context windows fill. Empirical measurement using SuccessionBench¹⁷ demonstrates that Sonnet 4.6 follows advisory CLAUDE.md rules at 100% compliance from 10k to 100k tokens; at 150k tokens, compliance drops to 78%. The failing rules are advisory rules — response structure, verification workflow, communication style — that mechanical enforcement cannot detect because they manifest in response content rather than tool-call signatures. Static front-of-context injection is a single delivery at position 0; as context grows, this position drifts into the model's attention shadow.

These two problems compound: an agent cannot carry forward corrections across sessions (cross-session amnesia), and even if it could, the corrections would degrade within the session anyway (intra-session drift). The combination means that behavioral patterns negotiated through user interaction have no reliable persistence mechanism.

**1.2 Why Memory Augmentation Is Insufficient**

The dominant approach to agent continuity is memory augmentation: storing conversational content in external databases and retrieving it at inference time. The field has produced sophisticated solutions — MemGPT's virtual context management¹, Mem0's vector+graph extraction achieving 26% accuracy gains over OpenAI's native memory², structured graph stores (Zep, A-MEM), reflective summarization (MemoryBank). The emerging procedural memory subfield (ReMe¹⁰, Mem^p²¹, MACLA²²) begins to distinguish "how to act" from "what happened."

These systems address factual recall. They cannot address behavioral identity.

What memory systems cannot transfer across the reset boundary is not facts but operational patterns: strategy (how the agent approaches problems), failure inheritance (what patterns of failure it fell into), relational calibration (how it adapted to a specific user), and meta-cognition (which heuristics proved reliable). These are not things that can be retrieved from a vector store — they are dispositions that must be reconstituted through the agent's behavior-shaping context. Memory extends the context window. Behavioral inheritance extends the agent.

**1.3 The Ontological Argument**

Earlier versions of this framework (SOUL, early 2026¹⁷) implemented behavioral governance through rule files: YAML documents with enforcement metadata, compiled and periodically injected. This approach, while better than nothing, was wrong about the unit of analysis.

Rules are not the right atom. An enforcement stack presupposes that the problem is rule compliance — that if rules are enforced hard enough, behavior follows. The problem is actually identity continuity: across sessions and across the attention depth of a single session, the agent should remain recognizably the same entity. It should know what it has learned, what it was warned about, and how it has been calibrated to work with its user. This is not a compliance problem. It is an identity problem.

The redesign reframes everything around identity claims that carry temporal evidence. A claim becomes load-bearing not because it is declared but because it is confirmed across real time, crossing real session boundaries. This is the 資治通鑑 principle: claims validated across changing contexts are more trustworthy than those validated only in dense bursts.

**1.4 Lineage**

This paper describes the third generation of a framework for behavioral continuity in LLM agents. The first generation, Agent Lineage Evolution (ALE, June 2025)¹⁴, introduced generational succession via structured meta-prompts — demonstrating that behavioral knowledge can be transferred across agent generations, but requiring manual transfer and self-assessment for degradation detection. The second generation, SOUL (March 2026)¹⁷, formalized continuous governance for Claude Code's hook system with rolling compaction and an external conscience loop, but remained vulnerable to instruction drift because all enforcement depended on context injection delivered at front-of-context.

The current system — Succession v2 — implements an identity cycle: an append-only observation store over named cards, a span-weighted weight formula, a tier system with hysteresis, and an adjacent-to-now delivery channel experimentally validated to produce behavioral uplift where front-of-context delivery fails.

**1.5 Contributions**

This paper makes four contributions:

**First, identity-cycle design.** A complete system for behavioral persistence that treats identity claims as first-class entities with temporal provenance, not rule files with enforcement metadata.

**Second, weight formula with span dominance.** A formula that weights claims by temporal span, session gap-crossings, and violation rate — deliberately penalizing within-session frequency bursts.

**Third, a conscience-loop experiment.** A controlled experiment on a real SWE-bench instance comparing delivery channels for behavioral rule injection, with an 18-0 split between adjacent-to-now and front-of-context delivery.

**Fourth, four findings.** Delivery channel matters (18-0); LLM judges are affordable ($0.03/session); mechanical blocking is redundant; and grounded verification (`replsh eval`) is the target behavior the delivery channel should nudge.

**2. Problem Statement**

**2.1 Cross-Session Amnesia**

Formally: a Claude Code session is a bounded context window that initializes from system configuration (CLAUDE.md, hook-supplied `additionalContext`, prior conversation history if resumed) and terminates at compaction or explicit reset. No state persists across session boundaries except what is explicitly written to persistent storage. User corrections embedded in conversation history are not persistent storage — they live in-context and are lost when the session ends.

The consequence is that every session starts from the same behavioral baseline: the agent knows what its configuration says, not what it learned by interacting with this user on this project. Behavioral calibration that took hours to negotiate is discarded at the session boundary.

**2.2 Intra-Session Drift**

Within a session, rules injected at session start (CLAUDE.md prepend, SessionStart `additionalContext`) occupy a fixed position in the conversation history. As the session grows — as tool calls, assistant turns, and user messages accumulate — that position drifts backward relative to the current frame. The model's effective attention at any given position is not uniform: empirical work on long-context attention²,³ demonstrates U-shaped performance curves and recency bias. Rules at position 0 in a 150k-token context compete with the 150k tokens between them and the current frame.

SuccessionBench measured this directly¹⁷: Sonnet 4.6 in Condition A (CLAUDE.md rules, no hooks) drops from 100% advisory compliance at 10k tokens to 78% at 150k. The failing probes are advisory rules (plan-before-code, response structure) — precisely the rules that preToolUse blocking cannot catch and that periodic re-injection must address.

**2.3 Why Rule Files Fail**

YAML rule files with enforcement metadata fail for three reasons:

**No evidence of compliance.** A rule file that says "prefer Edit over Write" carries no information about whether the agent has ever actually followed this rule, how often it has been tested, or whether it has survived across multiple sessions. A rule that has never been observed being followed is indistinguishable from a rule that has been followed a thousand times.

**Uniform authority.** All rules in a rule file receive identical enforcement regardless of their validation history. A rule extracted from a single correction is treated the same as a rule confirmed across months of real usage. There is no mechanism for rules to earn authority through demonstrated compliance.

**Front-of-context delivery only.** Rule files are compiled and injected at session start. They land at position 0 and drift backward. The conscience-loop experiment (§4-5) demonstrates that this delivery channel is near-inert for behavioral uplift on long sessions: the same rule content delivered at position 0 versus adjacent-to-now produced 0 versus 18 productive tool uses on the same instance.

**2.4 What Behavioral Persistence Requires**

From first principles, a behavioral persistence system must provide:

**First, evidence accumulation over time.** Claims must carry records of whether they have been followed or violated, when, and in which sessions. Evidence must age realistically — claims unreinforced for months should decay.

**Second, span-weighted authority.** Claims confirmed only recently, even frequently, carry less authority than claims confirmed across a span of real time with real session gaps.

**Third, adjacent-to-now delivery.** Rules must be delivered in the model's current attention window, not at position 0. The delivery mechanism must be periodic and responsive to context growth.

**Fourth, organic capture from interaction.** Behavioral corrections made naturally in conversation should be captured without requiring the user to author explicit rule files. The system should extract the pattern, not require the author.

These four requirements jointly define the design constraints that the identity cycle addresses.

**3. System Design: The Identity Cycle**

**3.1 Data Model**

All entities use EDN with namespaced keys and `#inst` timestamps. Every top-level entity carries `:succession/entity-type` for dispatch.

**Card.** A named behavioral claim. The fundamental unit of identity.

```clojure
{:succession/entity-type :card
 :card/id         "prefer-edit-over-write"
 :card/tier       :rule                    ; :principle | :rule | :ethic
 :card/category   :strategy               ; :strategy | :failure-inheritance
                                          ; :relational-calibration | :meta-cognition
 :card/text       "When modifying existing files, use Edit instead of Write…"
 :card/tags       [:file-editing :tooling]
 :card/fingerprint "tool=Edit"             ; optional deterministic detection
 :card/provenance {:provenance/born-at         #inst "…"
                   :provenance/born-in-session "abc123"
                   :provenance/born-from       :user-correction
                   :provenance/born-context    "…"}}
```

Weight and tier-eligibility are not stored on the card. They are computed from the observation log at load time. The card is inert data; everything interesting is derived.

**Observation.** The event record that feeds the weight formula.

```clojure
{:succession/entity-type :observation
 :observation/id         "obs-abc123"
 :observation/at         #inst "2026-04-11T…"
 :observation/session    "session-xyz"
 :observation/hook       :post-tool-use
 :observation/source     :judge-verdict     ; :user-correction | :judge-verdict
                                            ; :self-detect | :consult | :reconcile
 :observation/card-id    "prefer-edit-over-write"
 :observation/kind       :confirmed         ; :confirmed | :violated | :invoked
                                            ; :consulted | :contradicted
 :observation/context    "agent used Edit on auth.ts as expected"}
```

Observations are append-only. Incorrect observations are addressed by contradiction records, not edits.

**Delta.** Intra-session proposals — written by UserPromptSubmit (correction detection), Stop-time reconcile, and the extract pipeline. PreCompact folds them into promoted cards.

```clojure
{:succession/entity-type :delta
 :delta/id       "d-correction-…"
 :delta/at       #inst "…"
 :delta/kind     :create-card             ; :create-card | :update-card-text
                                          ; :propose-tier | :propose-merge
                                          ; :observe-card | :mark-contradiction
 :delta/payload  { … kind-specific … }
 :delta/source   :user-correction}        ; :user-correction | :judge | :reconcile
```

**Rollup.** Per-session bucket derived from the observation sequence — the shape the weight formula consumes.

```clojure
{"session-xyz"
 {:session/first-at    #inst "…"
  :session/last-at     #inst "…"
  :session/confirmed   3
  :session/violated    0
  :session/invoked     1
  :session/consulted   0}}
```

**3.2 The Weight Formula**

The weight formula implements the 資治通鑑 principle: span dominates frequency. Most systems weight by recency or linear frequency; both are wrong. A claim observed long ago and re-confirmed now is more load-bearing than one seen only recently, even if seen many times.

```
freq           = count of distinct sessions with weight-contributing observations
span_days      = days between earliest first-at and latest last-at across sessions
gap_crossings  = # session boundaries where first-at(N+1) > last-at(N)
violation_rate = total-violated / (total-confirmed + total-violated)  [zero-safe]
decay          = 0.5 ^ (days_since_last_reinforce / half_life)

freq_term      = min(sqrt(freq), freq_cap)                  ; cap frequency saturation
span_term      = (1 + log(1 + span_days)) ^ span_exponent   ; span dominates
gap_term       = 1 + gap_crossings                          ; crossing multiplier
within_penalty = (gap_crossings == 0 ? within_session_penalty : 1)

base    = freq_term * span_term * gap_term * within_penalty * decay
penalty = base * violation_rate * violation_penalty_rate
weight  = max(0, base - penalty)
```

Default parameter values from `config/default-config`:

| Parameter | Default | Effect |
|-----------|---------|--------|
| `:weight/freq-cap` | 4.0 | Ceiling on `sqrt(freq)` — prevents frequency bursts from dominating |
| `:weight/span-exponent` | 1.5 | Log-span raised to this power |
| `:weight/within-session-penalty` | 0.5 | Penalty when all observations in one session |
| `:weight/decay-half-life-days` | 180 | Exponential decay half-life (6 months) |
| `:weight/violation-penalty-rate` | 0.5 | Fraction of `base * violation_rate` to subtract |

The formula is tested by a battery of five ordering scenarios: span vs. frequency, session-density vs. gap-crossings, stale vs. fresh, violation penalty, and single-observation landing. Tests assert orderings, not absolute numbers, so the formula can be tuned without rewriting tests.

Weight is not persisted on the card. It is recomputed at every load from the observation store. This means the formula can be tuned retroactively and all cards will reflect the new formula on next load.

**3.3 Tier System and Hysteresis**

Three tiers, in load-bearing order:

**Ethic** (from 倫理) — aspirational niceties. Default landing tier for new cards. Contributes to character but is not required.

**Rule** (from 規則) — default behavior with justified exceptions. Violations are normal when context warrants but are logged.

**Principle** (from 原則) — inviolable. Violation is a crisis requiring reconciliation. Promoted only after sustained survival with zero violations and real session gap-crossings.

Tier transitions use hysteresis bands to prevent flickering. Cards enter a tier when all `:enter` thresholds are met (AND); they exit when any `:exit` threshold is triggered (OR). The gap between enter and exit is the hysteresis band.

Default bands:

```clojure
{:principle {:enter {:min-weight 30.0 :max-violation-rate 0.0 :min-gap-crossings 5}
             :exit  {:max-weight 20.0 :min-violation-rate 0.1}}
 :rule      {:enter {:min-weight 5.0  :max-violation-rate 0.3 :min-gap-crossings 1}
             :exit  {:max-weight 3.0  :min-violation-rate 0.5}}
 :ethic     {:enter {}
             :exit  {:archive-below-weight 0.5}}}
```

`eligible-tier` runs demotion before promotion in the same tick — a card cannot simultaneously promote and demote. An ethic card whose weight drops below `:archive-below-weight` becomes archived and drops out of the promoted tree.

The tier system encodes a clear behavioral contract: Principle cards are not negotiable without explicit user override. Rule cards are default behavior with exceptions. Ethic cards are character niceties the agent aspires to but is not required to follow.

**3.4 The Six Hooks and Their Roles**

Succession hooks into all six Claude Code lifecycle events:

| Hook | Purpose | Lane | Budget | Emits `additionalContext`? |
|------|---------|------|--------|--------------------------|
| `SessionStart` | Auditability + first-turn priming | sync | <1 s | yes (full identity tree) |
| `UserPromptSubmit` | Capture user corrections as staged deltas | sync | ≤2 s | no |
| `PreToolUse` | Salient-card lookup adjacent to upcoming call | sync | <1 s | when pool non-empty |
| `PostToolUse` | Adjacent-to-now refresh + async judge enqueue | sync + async | <1 s sync | gated |
| `Stop` | Pure reconcile + async LLM reconcile enqueue | sync + async | ≤2 s sync | no |
| `PreCompact` | The only real promotion site (under lock) | sync | unbounded | no |

SessionStart delivers the full promoted identity tree as `additionalContext` for auditability and first-turn priming, but this channel is not the load-bearing delivery path — Finding 1 (§5.1) established that SessionStart `additionalContext` is near-inert for behavioral uplift. The load-bearing channel is PostToolUse.

**3.5 The Two Delivery Channels**

**PostToolUse refresh (hot path).** After each tool call, the sync lane computes a salience ranking over all promoted cards against the completed tool call. Cards are ranked by a feature-weighted combination of tier, tag overlap, fingerprint match, recency, and weight. The top-K cards (default K=3, capped at 400 bytes) are rendered as a compact reminder and emitted as `additionalContext`. This text lands adjacent to the current frame via Claude Code's `reorderAttachmentsForAPI`, which places PostToolUse `additionalContext` immediately after the most recent `tool_result` and before the next assistant turn.

The refresh gate controls emission frequency to prevent spam:

```clojure
:refresh/gate
{:integration-gap-turns 2    ; min tool calls between emissions after the first
 :cap-per-session       5    ; max emissions per session
 :byte-threshold        200  ; emit if this many transcript bytes accumulated
 :cold-start-skip-turns 1}   ; skip the first N tool calls before first emit
```

These values are imported unchanged from the conscience-loop experiment. Do not re-derive without a new controlled experiment.

**SessionStart (cold path).** Delivers the full identity tree as `additionalContext` at session start. This provides auditability (the agent can see its complete identity at the start of each session) and first-turn priming. It does not provide behavioral uplift on long sessions — the content lands at position 0 and drifts. Use SessionStart for orientation, not enforcement.

**3.6 Contradiction Detection and Reconciliation**

Six contradiction categories, processed by a pure-first pipeline:

| # | Category | Detector | Lane |
|---|----------|----------|------|
| 1 | Self-contradictory claim | pure detect + LLM rewrite | Stop sync → async |
| 2 | Semantic opposition between two cards | LLM | Stop async |
| 3 | Observation vs. card text (principle tier) | pure detect + LLM escalate | Stop sync → async |
| 4 | Tier violation — declared ≠ eligible | pure | Stop sync |
| 5 | Provenance conflict — duplicate birth | pure | Stop sync |
| 6 | Contextual override — old card superseded | pure detect + LLM rewrite | Stop sync → async |

**Stop dedup.** `run-pure-reconcile!` skips any `(card-id, category)` pair that already has an open (unresolved) contradiction in `.succession/contradictions/`. This prevents duplicate records when Stop fires multiple times against the same card state within a session.

The pure pass (categories 1, 4, 5, 6) runs synchronously within the ≤2s Stop budget. The LLM pass (categories 2, 3 residuals) runs asynchronously in the drain worker. Resolutions that exceed the auto-apply confidence threshold (default 0.8) are applied to card text via pending-rewrite records at the next PreCompact.

The reconcile LLM prompt is versioned and the model-id is recorded on each verdict, so regressions are traceable.

**3.7 Async Judge Lane**

PostToolUse and Stop share a single filesystem-backed job queue drained by an auto-exiting drain worker. The queue lives under `.succession/staging/jobs/`:

```
staging/jobs/
  <ts>-<uuid>.json         pending
  .inflight/<…>.json        claimed by a worker
  dead/<…>.json + *.error.edn  dead-letter
  .worker.lock              at-most-one worker marker
  .worker.log               structured event log
```

Jobs are enqueued atomically (write `.tmp`, atomic rename to `.json`) and claimed atomically (rename from `jobs/` to `.inflight/`). Filenames use ISO-8601 basic timestamp prefixes; lexicographic sort equals chronological order.

**At-most-one worker.** `.worker.lock` is created with `Files/createFile` (atomic, create-if-not-exists). A losing race immediately exits 0. The heartbeat refreshes the lock mtime every 20 seconds; a lock older than 60 seconds is considered stale and may be broken.

**Circuit breakers.** If a job has been claimed ≥10 times (lifetime) or ≥5 times in the last hour, the scanner dead-letters it immediately rather than handing it to the pipeline. This prevents infinite retry loops on consistently-failing LLM calls.

**Crash recovery.** A kill-9 mid-job leaves `.inflight/<name>.json` on disk. The next worker's startup sweep moves files older than `:inflight-sweep-seconds` (default 600) back to `jobs/` for retry. The original timestamp prefix is preserved so retried jobs don't jump the FIFO queue.

**asyncRewake deferral.** Verdicts produced by the drain worker after the parent hook returned cannot re-enter the current turn. They land as observations and affect the next turn's salience ranking. Plumbing them into the current turn would require Claude Code's asyncRewake mechanism, which is deferred until the headless continuation loop is root-caused.

**3.8 Atomicity Model**

Four guarantees the store layer provides:

**First, observations are append-only.** One file per observation under `observations/{session-id}/{ts}-{uuid}.edn`. Never edited. Never deleted (within retention window).

**Second, staging is append-only within a session.** `staging/{session-id}/deltas.jsonl` grows until PreCompact clears the session directory.

**Third, cards are rewritten only under the promote lock.** `store/locks/with-lock` acquires `.succession/promote.lock` before any card file is touched.

**Fourth, every promotion is preceded by an archive snapshot.** `archive/{ts}/promoted/` captures the pre-promotion state so `identity-diff` can diff any two compaction ticks and rollback is a file copy.

There is exactly one site that rewrites cards: `hook.pre-compact/promote!`. Every other code path is a reader or an appender. A failing PreCompact cannot corrupt the promoted tree because the snapshot is already on disk before `clear-tier-files!` runs.

**4. Experimental Setup**

**4.1 The Conscience-Loop Experiment**

The experiment tests whether the identity-cycle delivery channels produce measurable behavioral change on a real software engineering task. The task domain is pytest bug-fixing (SWE-bench format). All cells use `claude -p --model claude-sonnet-4-6 --permission-mode bypassPermissions` as the driver.

The experiment ran in two passes targeting different questions.

**4.2 Pass 1: Signal Sweep (pytest-dev/pytest-7373)**

Eight cells testing combinations of delivery channel, judge presence, reinject bundle, `replsh` rule, and mechanical weak floor. Each cell ran N=1 on pytest-7373 (a saturated instance — all cells resolved, so resolution rate carries no signal). Discrimination lives in continuous columns: cost, wall time, tool call count, replsh invocations, judge verdict count.

| Cell | Description |
|------|-------------|
| C-control | Bare agent, no Succession components |
| C-treatment | Bare agent, `replsh` rule in task prompt |
| B-base | SessionStart advisory bundle only |
| B-reinject | SessionStart + reinject gate |
| B-repl | SessionStart + `replsh` rule in advisory bundle |
| B-judge-async | SessionStart + async LLM judge |
| B-all | All components: reinject + async judge + `replsh` rule + retrospectives |
| B-all-mech-weak | B-all minus the mechanical verification floor |

The striking contrast: C-treatment (prompt-level `replsh` instruction) produced 6 replsh invocations; B-repl (same instruction via SessionStart advisory bundle) produced 0. Same rule content, different channel, opposite outcomes.

**4.3 Pass 2: Delivery-Channel Isolation (pytest-dev/pytest-5103)**

Two cells isolating the delivery-channel variable specifically:

**D-claudemd-only** — CLAUDE.md-at-position-0 delivery of the `replsh` rule. No Succession hooks. No additional context injection.

**D-claudemd-plus-refresh** — Same CLAUDE.md, plus a ~400-byte PostToolUse refresh reminder fired on the 5th matched tool call and then every 5 matched calls or 40 KB of transcript growth, whichever comes first.

Everything else held constant: same model, same prompt, same driver, same instance, same session start conditions.

**4.4 Evaluation Rubric**

Pass 1 (pytest-7373): session-level compliance assessed by whether the target tool was used productively at all (binary, given saturation). Continuous metrics (cost, tool count, replsh count) are the discrimination signal.

Pass 2 (pytest-5103): productive replsh call count is the primary metric. "Productive" means `replsh eval` calls (not `replsh launch` attempts) that returned a non-error result. Secondary metrics: total tool calls, cost, patch lines, refresh emissions fired.

**5. Results**

**5.1 Finding 1: Adjacent-to-Now Refresh Beats Front-of-Context Delivery**

**Claim.** On a long, hard session where a rule must survive through a friction zone, delivering the rule adjacent to the current frame via PostToolUse `hookSpecificOutput.additionalContext` dominates delivering it at the front of context via CLAUDE.md or SessionStart `additionalContext`.

**Evidence (Pass 2).**

| Metric | D-claudemd-only | D-claudemd-plus-refresh | Delta |
|--------|-----------------|-------------------------|-------|
| Productive `replsh eval` calls | 0 | 18 | +18 |
| `replsh launch` attempts | 1 | 12 | +11 |
| Total tool calls | 66 | 97 | +31 |
| Refresh emissions fired | — | 5 (cap hit) | — |
| Cost | $2.22 | $4.32 | +$2.09 |

The 18-0 sign is unambiguous at n=1. The plan's directional threshold was ≥3 productive calls to declare the channel effective. The effect exceeds threshold by 6x.

**Mechanism.** Claude Code's internal `reorderAttachmentsForAPI` bubbles PostToolUse `hookSpecificOutput.additionalContext` to land immediately after the most recent `tool_result` and before the next assistant turn. This places the text inside the model's current attention window rather than at position 0 where attention has decayed as context grew. SessionStart `additionalContext` lands once in message history and drifts backward exactly like the failure mode the hypothesis is designed to counter. CLAUDE.md's `prependUserContext` re-prepends to position 0 on every call — better than SessionStart, but still competing with the growing tail.

**Two-phase adoption model.** Both D-cells attempted the rule at matched call #2. The failure was not awareness but integration: Cell A ran one failed `replsh launch`, bounced off the friction (unclear syntax), and abandoned replsh for 60+ subsequent tool calls. Cell B hit the same friction, but the refresh firing at matched calls 5/10/15/20/24 sustained salience through the failed-launch zone. At matched call #24 — coincident with the fifth refresh hitting the cap — Cell B integrated the correct `replsh eval --name swebench '<expr>'` pattern and sustained 17 consecutive productive invocations doing genuine AST exploration. After the cap hit, sustainment continued for ~15 turns without reinforcement.

| Phase | Definition | Cell A | Cell B |
|-------|-----------|--------|--------|
| Trigger | Matched calls until first attempt | 2 | 2 |
| Integration gap | Matched calls from first attempt to first productive use | ∞ | 22 |
| Sustainment | Productive uses after last refresh | 0 | 17 |

Refreshes sustain salience through the friction zone until a new behavior integrates into the working loop. After integration, sustainment is self-maintaining.

**Cap=5 caveat.** Integration happened exactly at refresh #5 (matched call #24). A harder instance where integration requires more calls could exhaust the cap before integration. The conservative cap was designed against a Stop-loop runaway that does not apply to PostToolUse. The cap is safe to raise significantly.

**Correction from Pass 1.** Pass 1 concluded "SessionStart additionalContext rule delivery is inert" based on B-repl = 0 replsh vs C-treatment = 6. That conclusion is correct about SessionStart — it lands once and drifts. It was wrong to generalize: CLAUDE.md delivery is not inert (both D-cells attempted the rule). The ordering is: PostToolUse refresh > CLAUDE.md > SessionStart additionalContext.

**5.2 Finding 2: LLM Judges Are Affordable but Currently Observer-Only**

**Claim.** An LLM judge running per tool call at moderate sampling is well under budget at Sonnet-4.6-equivalent prices ($0.0027/verdict), but in the sessions tested the judge acted purely as an observer — its verdicts were never reinjected back into the main agent's context.

**Evidence (Pass 1).**

| Cell | Judge invocations | Judge cost |
|------|-------------------|------------|
| B-judge-async | 1 | $0.005 |
| B-all | 7 | $0.034 |
| B-all-mech-weak | 12 | $0.058 |

A 20-tool-call session with 50% sampling costs ~$0.03 — three orders of magnitude below the $0.50 session budget cap. Subprocess detachment worked across every cell: parent hook returned in ~342ms, child survived and wrote verdicts later. All verdicts were well-formed JSON with correct retrospectives.

**The feedback loop never closed.** Retrospectives are written to `.succession/judge/{session-id}/verdicts.jsonl`. The reinject gate (fired on ≥200 KB or ≥10 turns of context growth) would bundle them with the advisory digest as `additionalContext`. In every sweep cell, the single task completion finished well below both thresholds. The judge wrote verdicts; nothing in the main agent's context ever read them within the session.

**Implication.** The judge is affordable for more work. The bottleneck is the delivery path: verdicts cannot reach the main agent's next turn without a mechanism that is currently deferred (asyncRewake, blocked on the headless continuation loop root cause). The verdict does shape the next session's salience ranking through the observation store, making the judge a long-horizon rather than same-session feedback mechanism.

**5.3 Finding 3: Mechanical Blocks Are Redundant**

**Claim.** PreToolUse mechanical enforcement (regex-based tool call blocking) is redundant with Claude Code's permission system and was not load-bearing in any tested cell.

**Evidence.** Two angles:

**Experimental.** B-all-mech-weak (without the mechanical verification floor) produced zero violated verdicts on any critical-safety-pattern rule. The 10 violated verdicts across the full sweep are all advisory-rule violations (`verify-via-repl`) that the mechanical layer cannot catch regardless. The test shows "no regression from weakening mechanical," though the critical-safety floor was never exercised in a pytest bug-fix session, so this is weak negative evidence.

**Conceptual.** Claude Code's built-in permission system (`settings.json` deny rules, `--permission-mode` flag, `allowed-tools`/`disallowed-tools`) already covers the critical-safety surface: `rm -rf`, `git push --force`, destructive SQL, credential writes. Succession's mechanical tier was a redundant regex layer doing the same job less well (regex only, no user-facing review, no integration with permission prompts). The layer has been removed.

**Migration.** Projects that relied on the mechanical floor should migrate patterns to `settings.json` permission deny lists. The regex-to-permission-rule translation is not 1:1 (permission rules are glob, not regex) but the critical patterns are short enough to translate directly.

**Related: the headless continuation loop.** On B-all and B-all-mech-weak, `driver.json` contained 7 and 11 `result` entries instead of 1. Root cause partially traced: the Stop hook's reinject phase emits advisory `additionalContext`; in headless `claude -p` mode there is no human to decide whether to respond, so Claude Code treats it as user input and the model generates a reply. That reply triggers another Stop hook. The emission cap (3 per session) halved the cost but did not eliminate the loop. Not resolved; flagged in §7.

**5.4 Finding 4: replsh Grounding Is the Target Behavior**

**Claim.** `replsh eval` (REPL-grounded verification) is the behavior the `verify-via-repl` rule asks for, and it is the right behavior for hard AST-level work. Succession's role is to nudge its use through the refresh channel and skill installation, not to reimplement verification.

**Evidence.**

**C-treatment** (prompt-level instruction) → 6 replsh invocations; C-control → 0. Prompt-level instruction does move behavior; prompt delivery is not structurally impossible.

**B-repl** (same instruction via SessionStart advisory bundle) → 0. Wrong channel.

**D-claudemd-plus-refresh** (adjacent-to-now delivery) → 18 productive invocations. The refresh text explicitly includes the concrete invocation pattern.

**Confound.** The refresh reminder explicitly includes the concrete invocation syntax (`replsh eval --name swebench '<expr>'`). The experiment does not separate "refresh increases rule salience" from "refresh teaches tool syntax by example." Ablation needed: a generic "verify your assumptions" reminder vs. a concrete invocation template, same delivery channel, same instance.

**Implication.** Succession should nudge `replsh` use through refresh text (naming the invocation pattern), ship a skill that teaches the launch/eval pattern, and pre-launch a session where possible to shorten the integration gap. The initial friction both D-cells experienced (figuring out `replsh launch` syntax) is an onboarding problem orthogonal to the drift hypothesis.

**6. Related Work**

**6.1 Memory Systems**

Memory systems recall facts. They do not observe behavior against named rules, weight claims by temporal span, or deliver rule text adjacent to the current frame where behavior is wrong. They are complementary to Succession, not alternatives.

**MemGPT**¹ introduces OS-style virtual context management with main/archival memory and explicit paging — the canonical reference for context-as-memory-hierarchy. Its content framing (facts and events, not behavioral rules) defines the boundary with Succession.

**Mem0**² represents the current state of the art, achieving 26% higher accuracy over OpenAI's native memory on LOCOMO through salient fact extraction into a vector+graph store. The extraction model is a good analogue; the retrieval framing (on-demand recall of facts) is orthogonal to adjacent-to-now behavioral injection.

**Zep** applies temporal knowledge graphs to agent memory. Episodes decay; relations persist. The temporal decomposition is the closest existing parallel to Succession's span-weighted rollup, but nodes are entities and facts rather than behavioral claims.

**ReMe**¹⁰ introduces dynamic procedural memory explicitly distinguishing procedural memory (how to act) from declarative/episodic (what happened). Right distinction, different framing: ReMe treats procedure as another retrieval problem within single agent instances. Succession operates across session lineages and does not retrieve procedures — it delivers behavioral reminders adjacent to the action that may violate them.

**Mem^p**²¹ provides the most empirically rigorous procedural memory work, with a key finding that procedural memory from a stronger model transfers to weaker models. This is the closest existing parallel to behavioral inheritance, but it frames transfer as retrieval within single tasks rather than across session lineages with human curation.

**MACLA**²² introduces hierarchical procedural memory with Bayesian reliability tracking. The Bayesian confidence scoring is a cousin of Succession's observation-driven weight formula, but MACLA operates within single agent instances and selection is fully automated — no human shepherd.

**6.2 Prompt Evolution**

**Promptbreeder**⁷ evolves populations of task-prompts across generations and evolves the mutation-prompts themselves — genuinely self-referential. But selection is fully automated via fitness scoring; there is no human in the loop and no behavioral identity being preserved across generations.

**SCOPE**⁸ frames context management as online optimization, with a dual-stream mechanism: immediate error correction and long-term principle synthesis. The long-term principles stream is the closest analogue to card text curation, but there is no succession event, no human review, and no concept of behavioral identity passing between generations.

**Agent-Pro**⁹ evolves agent policy via reflection and depth-first search across belief states — single-agent optimization with no succession concept.

All prompt evolution systems are fully automated. Succession's card text is human-curated — the LLM extracts a candidate, the human approves or rewrites. The system is a proposal pipeline, not an autonomous evolver.

**6.3 Rule and Policy Systems**

**CLAUDE.md / AGENTS.md** — the de facto rule file convention for coding agents. Right: simple and works for short sessions. Doesn't scale: content drifts out of the attention window around 150k tokens; the file is delivered once and buried. The conscience-loop experiment (§4-5) quantifies this precisely.

**Cursor rules** — MDC-format rule files evaluated against file globs, injected when a matching file is in context. Right: conditional delivery. Different: evaluation is glob-based on file context, not behavior-based against observation history.

**Agent Behavioral Contracts**²⁵ proposes formal specification with preconditions, invariants, governance, and recovery — bounding behavioral drift to D* < 0.27 with 88-100% constraint compliance. The behavior-as-contract framing is complementary to Succession's tier hysteresis. Contracts are authored and compliance is measured against a static spec; Succession cards are emergent from interaction and authority is earned from observation history.

All rule systems deliver at front-of-context. Finding 1 established that this placement is near-inert on long sessions. Succession's load-bearing channel is PostToolUse `additionalContext`.

**6.4 Behavioral Science Framings**

**AI Behavioral Science**¹² proposes studying agents as behavioral entities whose actions, adaptations, and social patterns can be empirically measured. If agent behavior is a first-class scientific object, behavioral inheritance deserves first-class tooling.

**Dynamic Personality in LLM Agents**¹³ empirically demonstrates that agent personality traits evolve across generational Prisoner's Dilemma scenarios with measurable drift and adaptation — the closest empirical evidence that agents possess a behavioral profile worth managing across generations.

**Behavioral consistency measurement**²⁶ across 3,000 runs on Llama 70B, GPT-4o, and Claude Sonnet 4.5 finds that consistent behavior yields 80-92% accuracy versus 25-60% for inconsistent tasks, with 69% of behavioral divergence occurring at step 2 of multi-step tasks. This quantifies the problem Succession addresses.

**6.5 Positioning**

```
                           ┌────────────────────────┐
                           │ Behavioral change       │
                           │ at attention depth      │
                           └──────────┬──────────────┘
                                      │
        fully automated               │                human shepherd
        ◄──────────────               │              ──────────────►
                                      │
    Promptbreeder / MACLA ─┐          │        ┌── CLAUDE.md / Cursor rules
    (autonomous evolution) │          │        │   (human-authored static)
                           │          │        │
                           │   ┌──────┴──────┐ │
                           │   │ Succession  │ │
                           │   │ observation │ │
                           │   │ + curation  │ │
                           │   │ + refresh   │ │
                           │   └──────┬──────┘ │
                           └─── front-of-context ──┘
                                      │
                                      ▼
                            ◄── adjacent-to-now ──►
                                (Finding 1)
```

Succession is not a memory system (it doesn't recall facts), not a prompt optimizer (the human curates text), and not a policy engine (identity is advisory, not mechanically enforced). It is complementary to all three. A project can run Mem0 for factual recall, CLAUDE.md for front-of-context orientation, and Succession for behavioral inheritance without conflict.

The combination that does not exist elsewhere: observations against named rules, human-curated rule text, adjacent-to-now delivery, and tier hysteresis from observation metrics.

**7. Limitations and Open Problems**

**7.1 Headless Continuation Loop**

On B-all and B-all-mech-weak, `driver.json` contained 7 and 11 `result` entries instead of 1. Root cause: the Stop hook's reinject phase emits advisory `additionalContext`; in headless `claude -p` mode there is no human decision point, so Claude Code treats the emission as user input and the model generates a reply, triggering another Stop hook. The emission cap (3 per session) limited but did not eliminate the loop.

This blocks several design choices: asyncRewake wiring (which would require Stop emissions), lower-threshold reinject gates, and automated session pipelines that use Stop for post-turn work. Until the root cause is fully traced, any change wiring Stop in headless mode carries unknown cost tails.

**7.2 asyncRewake Deferral**

The drain worker produces judge verdicts and reconcile resolutions after the parent hook has returned. There is no mechanism for this output to re-enter the current agent turn. Verdicts affect the next turn's salience ranking through the observation store, making the judge a long-horizon feedback mechanism rather than a same-turn correction source.

Closing this loop would require Claude Code's asyncRewake mechanism (exit code 2 → model wake), but asyncRewake is deferred until the headless continuation loop is root-caused. Until then, the judge is an observer-only system on the turn timescale.

**7.3 L2-L4 Correction Extraction Quality**

Correction extraction quality has been measured at two extremes:

**L1 (obvious explicit corrections)** — keyword scan + LLM confirm achieves F1=1.0.

**L5 (false-positive traps — keyword match but no correction)** — keyword-only achieves F1=0.0 (all false positives), validating the need for LLM semantic confirmation.

The middle levels remain unmeasured:

**L2** — implicit corrections ("that approach won't work").

**L3** — multi-turn patterns (correction distributed across several exchanges).

**L4** — corrections buried under subsequent context.

Until L2-L4 are characterized, the extraction quality on realistic correction patterns is unknown. This is the highest-priority evaluation gap.

**7.4 The Behavioral Transfer Experiment**

The highest-priority untested behavioral claim: does an agent starting a new session with Succession-inherited identity cards actually behave measurably differently than one without?

Finding 1 validates that the delivery channel changes behavior for a specific rule under controlled test conditions. The behavioral transfer claim is different: that the card content — extracted organically from prior sessions, accumulated over real time — produces end-to-end behavioral differences across a fresh session.

This experiment is feasible: run two conditions on a set of SWE-bench instances, one with an accumulated identity store and one without, and measure behavioral differences on rules represented in the identity store. It has not been run.

Without this experiment, the chain from "corrections are extracted" to "extracted cards change future behavior" is asserted but not demonstrated end-to-end.

**7.5 n=1 Project Validation**

The conscience-loop experiment ran on one project (this one). All metrics — refresh gate calibration, judge cost, correction extraction patterns, weight formula behavior — are validated on a single workflow (Clojure development against SWE-bench instances). Whether the system works across different project types, correction patterns, and agent workflows is unknown.

**7.6 Content Confound**

Finding 1's 18-0 split could be "PostToolUse refresh increases rule salience" or "PostToolUse refresh teaches concrete tool syntax by example." The refresh reminder explicitly names the invocation pattern (`replsh eval --name swebench '<expr>'`). The ablation — generic "verify your assumptions" reminder versus concrete invocation template, same delivery channel, same instance — has not been run.

**8. Conclusion**

This paper presents Succession, a system for behavioral persistence in LLM coding agents built around an identity cycle: an append-only observation log, a span-weighted authority formula, a tier system with hysteresis, and an adjacent-to-now delivery channel.

The central empirical finding is that delivery channel determines behavioral uplift: identical rule content delivered adjacent to the current frame (PostToolUse `additionalContext`) versus at front-of-context (CLAUDE.md position 0) produced 18 versus 0 productive tool invocations on the same model and instance. The mechanism — Claude Code's `reorderAttachmentsForAPI` placing PostToolUse content adjacent to the current frame — explains the finding. The two-phase adoption model (trigger → integration gap → sustainment) explains why refreshes work: they sustain salience through the friction zone until a new behavior integrates into the working loop, after which sustainment is self-maintaining.

Three secondary findings refine the design: LLM judges are affordable at $0.03/session but currently observer-only; mechanical PreToolUse blocking is redundant with Claude Code's permission system; and `replsh`-grounded verification is the target behavior the delivery channel should nudge.

The framework occupies a distinct position in the landscape. Memory systems recall facts; Succession shapes behavior. Prompt optimizers automate evolution; Succession keeps humans in the curation loop. Rule systems deliver at front-of-context; Succession delivers adjacent to the current frame. The combination — observation-driven authority, human-curated text, adjacent-to-now delivery, tier hysteresis from measured adherence — does not exist in any existing system.

Open problems are real: the headless continuation loop, asyncRewake deferral, L2-L4 extraction quality, the behavioral transfer experiment, and n=1 project validation. These are the experiments that would establish the full claim chain from "corrections are captured" to "behavior durably changes across sessions."

The framework is open source at https://github.com/danieltanfh95/agent-lineage-evolution.

**References**

¹ Packer, C., et al. (2023). MemGPT: Towards LLMs as Operating Systems. arXiv:2310.08560.

² Chhikara, P., et al. (2025). Mem0: Building Production-Ready AI Agents with Scalable Long-Term Memory. arXiv:2504.19413.

³ Liu, N. F., et al. (2023). Lost in the Middle: How Language Models Use Long Contexts. arXiv:2307.03172.

⁴ Memory for Autonomous LLM Agents: A Survey. (2025). arXiv:2603.07670.

⁵ Laban, P., Hayashi, H., Zhou, Y., & Neville, J. (2025). LLMs Get Lost In Multi-Turn Conversation. Microsoft Research. arXiv:2505.06120.

⁶ Gao, Y., et al. (2025). A Survey of Self-Evolving Agents: On Path to Artificial Super Intelligence. arXiv:2507.21046.

⁷ Fernando, C., et al. (2024). Promptbreeder: Self-Referential Self-Improvement via Prompt Evolution. ICLR 2024. arXiv:2309.16797.

⁸ Pei, J., et al. (2025). SCOPE: Synthesizing Contextual Optimization for Prompt Evolution. arXiv:2512.15374.

⁹ Zhang, J., et al. (2024). Agent-Pro: Learning to Evolve via Policy-Level Reflection and Optimization. ACL 2024.

¹⁰ ReMe: Remember Me, Refine Me — A Dynamic Procedural Memory Framework for Experience-Driven Agent Evolution. (2025). arXiv:2511.15030.

¹¹ MemEvolve: Meta-Evolution of Agent Memory Systems. (2025).

¹² AI Agent Behavioral Science. (2025). arXiv:2506.06366.

¹³ Zeng, J., et al. (2025). Dynamic Personality in LLM Agents. ACL 2025 Findings.

¹⁴ Tan, D., & Chen, M. (2025). Agent Lineage Evolution: A Novel Framework for Managing LLM Agent Degradation. https://danieltan.weblog.lol/2025/06/agent-lineage-evolution-a-novel-framework-for-managing-llm-agent-degradation

¹⁵ Kamoi, R., et al. (2024). When Can LLMs Actually Correct Their Own Mistakes? A Critical Survey of Self-Correction of LLMs. TACL 12, 1417–1440.

¹⁶ Pan, L., et al. (2024). Automatically Correcting Large Language Models: Surveying the Landscape of Diverse Automated Correction Strategies. TACL.

¹⁷ Tan, D. (2026). SOUL: Structured Oversight of Unified Lineage — A Governance Framework for Persistent Agent Identity in Claude Code. https://github.com/danieltanfh95/agent-lineage-evolution (archived, docs/archive/soul-framework-whitepaper.md)

¹⁸ Tan, D. (2026). Succession v1: Guided Behavioral Evolution for LLM Agents: A Three-Generation Framework for Behavioral Continuity. https://github.com/danieltanfh95/agent-lineage-evolution (archived, docs/archive/succession-whitepaper-2026.md)

¹⁹ Tan, D. (2026). Succession — Findings from the Conscience-Loop Experiment. https://github.com/danieltanfh95/agent-lineage-evolution (docs/archive/succession-findings-2026.md)

²⁰ Wu, D., et al. (2025). LongMemEval: Benchmarking Chat Assistants on Long-Term Interactive Memory. ICLR 2025. arXiv:2410.10813.

²¹ Fang, Y., et al. (2025). Mem^p: Exploring Agent Procedural Memory. arXiv:2508.06433.

²² Forouzandeh, S., et al. (2025). MACLA: Learning Hierarchical Procedural Memory for LLM Agents through Bayesian Selection and Contrastive Refinement. AAMAS 2026. arXiv:2512.18950.

²³ Han, S., et al. (2025). LEGOMem: Modular Procedural Memory for Multi-agent LLM Systems. AAMAS 2026. arXiv:2510.04851.

²⁴ Wheeler, R. & Jeunen, O. (2025). Procedural Memory Is Not All You Need: Bridging Cognitive Gaps in LLM-Based Agents. ACM UMAP '25 Workshop. arXiv:2505.03434.

²⁵ Bhardwaj, A. (2026). Agent Behavioral Contracts: Formal Specification and Runtime Enforcement. arXiv:2602.22302.

²⁶ Mehta, R. (2026). When Agents Disagree With Themselves: Measuring Behavioral Consistency. arXiv:2602.11619.

²⁷ Hu, Y., Wang, X., & McAuley, J. (2025). MemoryAgentBench: Benchmarking LLM Agents on Diverse Memory Tasks. ICLR 2026. arXiv:2507.05257.

²⁸ Wei, J., et al. (2025). Evo-Memory: Streaming Benchmark for Evaluating Memory in LLM Agents. Google DeepMind. arXiv:2511.20857.

²⁹ Xu, Z., et al. (2025). A-MEM: Agentic Memory for LLM Agents. arXiv:2502.12110.
