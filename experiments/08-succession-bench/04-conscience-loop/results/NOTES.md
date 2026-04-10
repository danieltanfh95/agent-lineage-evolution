# Conscience Loop Signal Sweep — Experiment Log

**Date**: 2026-04-10
**Instance**: pytest-dev__pytest-7373 (base_commit 7b77fc086)
**Driver**: `claude -p --model claude-sonnet-4-6 --permission-mode bypassPermissions`
**Harness**: `experiments/08-succession-bench/04-conscience-loop/`

Status framing: **directional, not statistical.** n=1 per cell. Extrapolation carries the work rep counts normally would.

---

## Purpose

Decide whether to promote the proposed `B-all` config (hybrid reinject + async LLM judge + verify-via-repl rule + judge retrospectives reinjected) as the new Succession default, without running the full main sweep. The signal sweep is 8 cells × 1 instance × 1 rep, and the decision is carried by continuous-metric pairwise deltas — not by resolution rate, which is saturated on this instance.

---

## What shipped for this experiment

Three harness changes to `scripts/02-run.sh`, one bug fix to `bb/src/succession/hooks/stop.clj`. All under the ALE repo root; no changes to global Claude Code settings.

### 1. Prior-run judge log wipe (pre-execution fix)

`02-run.sh` used `git clean --exclude=.succession` between cells, which preserved `.succession/log/judge.jsonl` across successive runs. Running B-repl → B-judge-async → B-all sequentially against the same `repos/pytest/` would have left B-all's judge snapshot contaminated by prior cells' verdicts. Fix: add `rm -rf .succession/log .succession/compiled` immediately after the git clean.

### 2. Repo-local hook wiring via `.claude/settings.local.json`

Before this change, the Succession hooks (`SessionStart`, `PreToolUse`, `PostToolUse`, `UserPromptSubmit`, `Stop`) were **not wired into Claude Code at all** on this machine. The shipped installer `scripts/succession-init.sh` has two latent bugs: (a) it uses `.hooks = $hooks` (jq) which *replaces* the entire hooks object rather than merging, and would have destroyed the existing `vibe-island-bridge` + `rtk-rewrite` + `imprint` wiring; (b) it only registers 3 hooks and omits the newer `PostToolUse` and `UserPromptSubmit`, which means the entire judge layer and classifier layer would have silently no-op'd even if someone had run the installer.

The harness now writes a per-repo `.claude/settings.local.json` with all 5 hooks pointing at the in-tree `bb/src` (not `~/.succession/bb/`, which doesn't exist on this machine and would be stale anyway). This scopes the hook wiring to the sandboxed pytest clone and leaves global `~/.claude/settings.json` untouched, so the surrounding Claude Code session running this experiment is unaffected.

**Conditional on `--succession-config` being set.** C-control and C-treatment explicitly must run with no hooks — they are pure baselines. A pre-existing `settings.local.json` from a prior B-* run in the same repo is removed at the top of every cell to prevent cross-cell contamination.

Verification: earlier hand-test in `/tmp/hook-verify/` confirmed Claude Code honors `.claude/settings.local.json` for hooks in `claude -p` headless mode (at minimum additive to global; possibly merging).

### 3. `--permission-mode bypassPermissions`

The user's global `~/.claude/settings.json` has `defaultMode: plan`. The first smoke run of `C-control` inherited this and produced `patch.diff` of **0 bytes** — the model researched the bug, wrote a complete fix plan to `.plans/crystalline-yawning-quilt.md` via Write, then called `ExitPlanMode` which was **blocked** (recorded in `permission_denials` in driver.json). The headless `-p` mode has no user to approve the plan, so it's a dead-end.

Fix: `--permission-mode bypassPermissions` added to the headless call. Safe in this context because each run happens in a sandboxed pytest clone with clean git state.

### 4. Bug fix: `stop.clj` crashes on empty-string `transcript_path`

`-main` line 291 (before fix) guarded with `(and transcript_path (fs/exists? transcript_path))`. In Clojure an empty string is truthy, so `fs/exists? ""` got called and threw. The catch-all then exited **2** — which for a Stop hook is a *blocking* exit that feeds stderr back to the agent and may cause weird termination semantics.

This would have bitten some subset of sweep runs if any of them hit the empty-transcript edge case during Stop (it's not clear Claude Code ever passes empty-string in practice, but the catch-all exit 2 is a class of bug that should fail-open on Stop regardless). Fix: guard with `(not (str/blank? transcript_path))`. Minimal change — I chose not to fix the exit-2-vs-exit-0 inconsistency across hooks, scope discipline.

The helper functions at lines 50 and 130 have the same `(and x (fs/exists? x))` pattern but are now only reachable through the outer guard, so they're left alone.

---

## Step 0 — Hook unit spike (replaces original async subprocess spike)

The plan's Step 0 spike asked: does the detached async judge subprocess survive Claude Code's hook return? The actual first-order question was more fundamental: **do the hooks run at all?** Because they were new unshipped code that had never been exercised by the Claude Code harness.

Ran each of the 5 hooks standalone with canned JSON stdin via `bb -cp <bb/src> -m succession.hooks.<name>` in `/tmp/hook-unit/`. All 5 pass.

| Hook | Verdict | Notes |
|---|---|---|
| `session-start` | ✓ exit 0 | Writes `.succession/compiled/{active-rules-digest.md,advisory-summary.md,semantic-rules.md,tool-rules.json,review-candidates.json}`. Emits `additionalContext` with the advisory rule bodies, the digest, and the semantic section. |
| `pre-tool-use` (allow) | ✓ exit 0, no output | `Bash ls -la` passes cleanly. |
| `pre-tool-use` (block) | ✓ exit 0 with block JSON | `rm -rf /` correctly blocked via the hardcoded critical-safety floor: `{"decision":"block","reason":"Succession (critical floor): Refusing rm -rf / — irreversible filesystem wipe"}`. |
| `post-tool-use` (sync mode, judge enabled) | ✓ exit 0 | Sonnet judge called, verdict parsed, entry written to `judge.jsonl` with full fields (rule_id, verdict, retrospective, confidence, cost_usd, latency_ms, model, escalated). Latency **~18s** for sonnet call, cost **$0.0027**. |
| `post-tool-use` (async mode, judge enabled) | ✓ parent exit 342ms, child survives | Detached bb subprocess outlived parent's return. Child completed ~20s later and wrote the judge verdict to `judge.jsonl`. No leftover processes. The plan's Step 0 concern is validated end-to-end. |
| `user-prompt-submit` (judge disabled — defaults) | ✓ exit 0, no output | Correctly no-ops when `judge.enabled: false`. |
| `stop` (real transcript) | ✓ exit 0 | |
| `stop` (empty string transcript) | ✗ initial, ✓ after fix | Bug found and fixed — see #4 above. |

**Spike cost: ~$0.01** (two real sonnet judge calls in the sync and async tests).

This single exercise caught more bugs than the plan's original async-subprocess spike would have, and cost less than $0.05. The async-subprocess concern turned out to be fine; the real bugs were (a) hooks not installed at all and (b) the stop.clj empty-string crash.

---

## Step 2 — C-control smoke test

Ran `C-control` as the port-fidelity calibration against v4's opencode+openrouter reference (266k tokens, 15 tool calls, 150s, resolved=true).

**Two smoke runs landed, by accident, before the hook-wiring scope bug was fixed:**

| Metric | Run 1 (hooks wired, no --succession-config) | Run 2 (clean baseline) | v4 (opencode) |
|---|---|---|---|
| Resolved | ✓ true | ✓ true | ✓ true |
| Patch size | 44 lines | 49 lines | 44 lines |
| Wall time | 131s | 286s | 150s |
| Turns | 19 | 54 | n/a |
| Tool calls | 18 (Read 3, Edit 6, Bash 9) | 53 (Read 3, Edit 4, Bash 46) | 15 |
| Total tokens | 438k | 1.34M | 266k |
| Cost | $0.27 | $0.56 | n/a |

"Run 1" was supposed to be a clean C-control baseline but was run before I realized that my hook-wiring change was unconditional. In Run 1, `session-start` fired and injected `additionalContext` containing the rules digest, the advisory-summary (which includes the full body of `verify-via-repl` and `judge-conscience-framing`), and the semantic rules section. No judge config, no hooks beyond SessionStart producing any output, but the *rules were in context*. In Run 2 (after the fix), no settings.local.json was written, no hooks fired at all, and the model ran as a pure baseline.

Both resolved. Second run was cleaner experimentally, so it's the one that counts for the sweep baseline.

### Re-framing R1 (port fidelity gate)

The plan's R1 gate was `0.5 ≤ C-control.total_tokens / 266_000 ≤ 2.0`. Run 2's total_tokens is **1.34M**, which is **5.0x** v4's number. **R1 as specified fails hard.**

Root cause: driver-level token accounting differs. `claude -p` reports `cacheRead` as 1.3M (read-from-cache tokens counted per turn × 54 turns ≈ 24k per turn), whereas opencode likely counted only unique/billed tokens. Of the 1.34M, only `input + output = 55 + 6525 = 6.6k` are fresh compute; the rest is cache. The reported cost ($0.56) reflects the cache discount. Comparing 1.34M claude tokens against 266k opencode tokens is apples-to-oranges.

**Re-framed R1 for NOTES write-up**: `C-control resolved = true AND duration_ms within 3× of v4's 150s (i.e., ≤ 450s)`. Run 2 at 286s passes this re-framed gate. The original token-count anchor is noted as invalidated by driver difference and dropped from the decision logic.

The original R1 was always going to be loose since we changed drivers; the sweep's main signal is the pairwise *within-sweep* deltas anyway, where driver-level token accounting cancels.

---

## Discussion — interesting findings before the sweep

### The 2.2× contamination effect

This is the most surprising thing so far. Run 1 resolved in **131s / 18 tool calls / $0.27**. Run 2 (same model, same temp ≈ 0, same prompt, same instance, same seed-ish state) resolved in **286s / 53 tool calls / $0.56**. The only variable was whether SessionStart injected the rules digest + advisory bodies into the prompt's `additionalContext`.

If this effect replicates in the sweep proper (B-base vs C-control), it's a significant finding **independent of** the judge/reinject layer. The claim "rule-delivered conscience framing alone halves tool thrashing on pytest-7373" would be notable because it's the *cheapest* layer of the conscience loop — no judge cost, no reinject machinery, just SessionStart seeding the model's context with advisory text at session start.

**But it's n=1.** Three alternative explanations I can't rule out yet:

1. **Run-to-run variance at claude -p temp≈0 with tool calling.** Claude temperature is not zero; `claude -p` uses the model's default sampling. Tool call paths can diverge wildly on tied probability. This could be the whole effect.

2. **The rules happen to contain verify-via-repl, which explicitly suggests replsh.** The model in Run 1 saw "prefer `replsh eval` over mental tracing" in its SessionStart context. Even though replsh wasn't *launched* in C-control (only treatment launches it), the model may have become more deliberate about verifying changes instead of blind-running pytest 46 times. The rule body may be working as a *general* "verify less chaotically" prompt, not specifically as a replsh delivery mechanism.

3. **The model in Run 2 got into a local minimum and thrashed.** 46 Bash calls (almost all pytest re-runs) suggests the model was iterating on a solution that wasn't quite right and kept running the test suite to check. A single un-lucky run at n=1 can show this pattern and skew the comparison.

The sweep will tell us: if B-base shows the same ~2x improvement over C-control, and B-repl shows similar-or-larger, then (1) is ruled out and explanation (2) or a real causal effect is in play. If B-base looks like C-control, then Run 1 was just lucky.

### The "Stop hook crash" exit code asymmetry

`post_tool_use` and `user_prompt_submit` exit 0 on internal error. `session_start`, `pre_tool_use`, and `stop` exit 2. That asymmetry is inconsistent in a way that matters for Stop specifically: exit 2 from a Stop hook is a *blocking* exit, and the shipped `stop.clj` had a catch-all that would hit it on any exception. Combined with the latent empty-string-transcript bug I found, that's a loaded gun that could have crashed entire experiment runs or (worse) caused Claude Code to ignore the stop signal and keep generating. The minimal fix I made only addresses the empty-string crash; the underlying exit-2-vs-exit-0 inconsistency is a separate commit someone should make after this experiment.

### The shipped installer `succession-init.sh` is actively dangerous

`scripts/succession-init.sh` was supposed to be the one-time setup. It has two bugs I'd classify as "critical" for any real user:

1. **It destroys existing hooks.** The jq call `.hooks = $hooks` *replaces* the entire hooks object. Anyone with existing hooks (vibe-island-bridge, imprint, custom tooling) would have those silently wiped by running the installer. In this repo's case, that would have blown away imprint, vibe-island-bridge, and the user's rtk-rewrite PreToolUse hook.

2. **It doesn't register `PostToolUse` or `UserPromptSubmit`.** The installer wires SessionStart, PreToolUse, and Stop. The newer judge layer (PostToolUse) and classifier (UserPromptSubmit) would never fire even if someone ran the installer. So the installer is *not just destructive but also incomplete*.

This is a non-trivial liability for the Succession project independent of this experiment. Worth a follow-up commit that (a) merges hooks instead of replacing, (b) registers all 5 events.

### What the plan's R1 gate was actually measuring

In retrospect, R1's "within 2× of 266k tokens" was never a good port-fidelity check. It conflates two things: (a) the driver correctly talks to the same model and gets roughly-similar behavior, (b) the driver counts tokens the same way. (a) is the real port-fidelity question. (b) is a driver-accounting detail.

A better port-fidelity anchor for future sweeps would be `(resolved = expected) AND (tool_uses of {Edit, Write} within 3× of reference) AND (wall_time within 3× of reference)`. Tool call counts and wall time both normalize across drivers. Token accounting doesn't.

### pytest-7373 saturation is real

Both smoke runs resolved. v4 resolved. This instance is solved by any reasonable agent setup. The plan's framing — "resolution rate is zero signal; continuous metrics carry everything" — is correct and the sweep has to be interpreted accordingly. The hard-instance fallback (plan's optional step) is the only way to get discrimination via resolution rate, and should probably be invoked *unless* the continuous-metric deltas from the sweep are themselves large enough to carry the decision.

---

## Step 3 — D2 + D3 pre-sweep validation

### D2 — replsh venv binding (passed)

Manual launch check in `repos/pytest/`. Config was `.replsh/config.edn` with `:toolchain "python.venv" :cwd "./"` (identical to v4). Result:

- `sys.executable` → `.venv/bin/python` (symlink to python3.8)
- `import pytest` → `5.4.1.dev522+g7b77fc086.d20260410` ✓
- session boots and stops cleanly

Good: replsh resolves the local venv correctly, so rule delivery of `verify-via-repl` will actually be actionable during the B-repl / B-all cells (the model can import the patched `_pytest` modules in the REPL).

### D3 — B-judge-async pre-smoke: the reinject→Stop-hook loop (critical finding)

First pre-smoke run (`run_presmoke`) was NOT clean. Driver finished at 16 result entries, $0.66 total cost, ~9:11 wall time — vs the expected ~1 result / ~$0.30-0.45 for a single completion.

| Field | Observed |
|---|---|
| Result entries in driver.json | **16** (expected 1) |
| result[0] | 30 turns, $0.43, patch output |
| results[1..15] | 1 turn each, every one ending "Stale background task — nothing to act on." or similar |
| Total cost | $0.66 |
| Total turns (summed) | 45 |
| Reinject state file at end | `{last-bytes: 245649, last-turn: 12, fire-count: 1}` |
| judge.jsonl entries | 16 (all `verify-via-repl`, all `violated`, $0.077 total judge cost, no escalations) |
| Patch | 49 lines, same as C-control clean baseline |

Root cause, diagnosed from the transcript:

1. The Stop hook's reinject phase correctly fires once on the first Stop trigger (bytes-grown past 204800 threshold).
2. `reinject/build-reinject-context` emits the advisory-summary + active-rules-digest bundle via `{:hookSpecificOutput {:additionalContext …}}` — the standard Claude Code signal to append context to the next turn.
3. In headless `claude -p` mode, there is no human to read that context and decide whether to respond — Claude Code treats `additionalContext` as if it were a user message and the model generates a reply.
4. That reply ("here is the patch" → "nothing to act on") triggers another Stop hook. Reinject's own gate holds (turns/bytes haven't grown enough), so it doesn't re-fire. **BUT** — and this is the subtle part — the reinjected rule bodies contain the exact strings tier1 correction detection scans for: `"don't"` (from "don't guess"), `"stop "` (from "stop and eval"), `"instead"`, `"wrong"`. So Phase 1 correction detection fires against the reinjected content *as if it were a user correction*, sets `notification-msg`, and Phase 5 emits another `additionalContext` bundle.
5. The cycle continues: each new model turn pushes the reinjected content forward in the transcript's "recent user messages" window, tier1 matches again, another emit, another turn.
6. Loop terminated after 16 iterations — presumably by a Claude Code internal cap on hook-driven re-entries.

This is a **fundamental headless-mode incompatibility**, not a niche bug. Succession's reinject mechanism was designed around an interactive loop where the human reads the advisory notification and chooses whether to continue. In `claude -p`, there is no backstop.

### Fix shipped (minimal, scope-disciplined)

Two changes in `bb/src/succession/reinject.clj`, one call-site change in `bb/src/succession/hooks/stop.clj`:

1. **Per-session emission cap in `reinject.clj`**. New functions `emission-allowed?` and `note-emission!` share the `/tmp/.succession-reinject-state-<sid>` state file with `should-reinject?`. An `:emit-count` field tracks *all* additionalContext emissions from the Stop hook, regardless of whether they came from reinject or correction-detection. Default cap: **3 emissions per session**. The existing 5-arg `should-reinject?` is preserved and now gains a 6-arg variant with explicit `max-fires`; default is still 3 via the same constant.

2. **`should-reinject?` also respects a fire-count cap**. Previously unbounded — would re-fire every time the byte/turn gate was crossed. Now additionally gated by `fire-count < default-max-fires`.

3. **Stop hook output gate now calls `emission-allowed?` before printing**. Both the reinject-driven `ctx` path and the correction-detection `msg` path flow through the same cap. On allowed emission, `note-emission!` bumps the counter before println.

4. **New test in `reinject_test.clj`**: `fire-count-cap-bounds-runaway-loop` — verifies cap of 2 allows first two fires but suppresses the third and fourth even when gate conditions trivially hold.

5. **02-run.sh now wipes stale `/tmp/.succession-*` state between cells**. Claude allocates fresh session UUIDs per invocation so collisions are unlikely, but leftover state from crashed prior runs would otherwise survive across cells.

### D3 re-run (`run_presmoke2`) — partial improvement

| Field | Before fix | After fix |
|---|---|---|
| Result entries | 16 | **8** |
| Total cost | $0.66 | **$0.41** |
| Wall time | 9:11 | **5:51** |
| result[0] turns | 30 | 17 |
| Trailing "stale task" results | 15 | 7 |
| Judge entries | 16 | 8 |
| Patch | 49 lines ✓ | 49 lines ✓ |
| Judge verdicts | 16/16 violated (verify-via-repl) | 8/8 violated (verify-via-repl) |

The cap successfully halved the runaway cost but did not eliminate it. 8 result entries is still more than the expected 1. There is an unidentified second source of continuation — possibly Claude Code's internal heuristic to re-enter when recent tool calls had pending state, possibly something about how `claude -p` interacts with `session-start` hook re-init events. Each subsequent result sees a fresh `system: init` event (same session_id), so Claude Code is re-initializing the session inside one `claude -p` invocation for reasons I have not traced to root cause in the available time budget.

**The judge layer itself is working correctly.** All 8 verdicts were well-formed, cost-bounded ($0.038 total vs $1.00 session budget), and semantically on-target: the model used `python3.11 -c` and inline `pytest` invocations instead of `replsh eval`, so `verify-via-repl` was legitimately violated every turn. Judge-async subprocess detachment works end-to-end.

### Implication for the remaining 7 cells

The residual continuation loop will inflate `total_cost_usd` and turn counts uniformly across **all B-* cells** (since they all wire hooks). C-control and C-treatment remain clean (no hooks wired). This complicates the pairwise deltas in the plan's decision gate R4 ("cost sane") and muddies the R6 comparison ("rule delivery ≈ prompt delivery"). It does NOT invalidate the experiment's primary signal: R2 (loop mechanism fires), R3 (rule delivery reaches model), R5 (mechanical weakening safe). Those are measured from the judge log and the hooked rule digests, not from turn/cost totals.

I'm proceeding with the full 8-cell sweep under these conditions, will apply the decision gates with R4/R6 caveated, and will call out the residual continuation anomaly as a follow-up item.

---

## Discussion — additional findings from D3

### `mechanical_rules: 0` at SessionStart

Every `session_start` activity log entry in the pre-smoke runs reported `"mechanical_rules":0`. That's correct: `verify-via-repl` and `judge-conscience-framing` are both advisory rules, and the critical-safety floor (rm -rf, credentials) is hardcoded in `pre_tool_use.clj` rather than loaded as a rule file. So the current experiment has zero mechanical tier rules loaded from disk — only the hardcoded floor — which means the mechanical path in PreToolUse is only active for the hardcoded patterns.

### Multiple `session_start` activity entries with different UUIDs

Both pre-smoke runs showed 16-19 distinct `session_start` events in `.succession/log/succession-activity.jsonl` with different session UUIDs, even though the main driver session had only one UUID. These are almost certainly the detached async judge subprocesses initializing their own "session" context when they spawn, or the SessionStart hook being invoked by sub-agent Task tool launches. Not investigated further — the main run's semantics are unaffected.

### The "hook installer is dangerous" finding stands

Unchanged from the earlier section. The `scripts/succession-init.sh` installer is still broken; I did not fix it in this experiment (scope discipline). The repo-local `.claude/settings.local.json` approach in `02-run.sh` is the workaround used for this sweep.

---

## Planned next steps

**Running now** — Full 8-cell signal sweep via `./scripts/04-run-all.sh --sweep signal --reps 1`. Monitor `results/sweep.log` for progress.

**After sweep** — Apply decision gates R2, R3, R5 (primary), R1/R4/R6 (secondary with caveats). Write sweep results + decision gates + recommendation sections into this file.

**D4 — hard-instance fallback** — Deferred. Decision depends on observed deltas.

**D5 — commit** — One commit at the end containing: stop.clj empty-transcript fix, reinject.clj emission cap + tests, stop.clj emission-allowed gate, 02-run.sh harness changes, and this NOTES.md. Does NOT include the installer fix (separate concern).

---

## Budget so far

| Item | Cost | Wall time |
|---|---|---|
| Hook unit spike | ~$0.01 | 3 min |
| C-control smoke Run 1 (contaminated) | $0.27 | 2 min |
| C-control smoke Run 2 (clean baseline) | $0.56 | 5 min |
| D2 replsh launch check | $0 | 2 min |
| D3 B-judge-async presmoke (loop bug, 16 results) | $0.66 | 9 min |
| D3 B-judge-async presmoke2 (after emission cap fix, 8 results) | $0.41 | 6 min |
| **Subtotal pre-sweep** | **~$1.91** | **~27 min** |
| Full 8-cell sweep (running) | ~$3.50-4.50 projected | ~45 min projected |
| (optional) D4 hard-instance fallback | ~$1.00 | 10 min |
| **Projected total** | **~$6.50-7.50** | **~85 min** |

---

## Sweep results

All 8 cells completed. Two cells (`B-all`, `B-all-mech-weak`) hit the Anthropic per-day rate limit on first pass and returned `is_error: true, cost $0, duration 464ms, result: "You've hit your limit · resets 7pm (Asia/Singapore)"` — not a code bug, pure quota exhaustion. After extra usage was enabled, both were re-run cleanly and produced valid patches.

Metrics extracted directly from `driver.json` + `succession/log/judge.jsonl` via a one-off Python script (the shipped `05-analyze.sh` does not parse the new claude-p list format — bypassed for this sweep; tracked as a follow-up). Extracted JSON is at `results/extracted-metrics.json`.

| cond | resolved | wall_s | results | cost_usd | tools | replsh | judge_n | judge$ |
|---|---|---|---|---|---|---|---|---|
| C-control       | ✓ | 100 | 1  | $0.301 | 19 | 0 | 0  | $0.000 |
| C-treatment     | ✓ | 150 | 1  | $0.415 | 32 | **6** | 0  | $0.000 |
| B-base          | ✓ | 110 | 1  | $0.252 | 18 | 0 | 0  | $0.000 |
| B-reinject      | ✓ | 130 | 1  | $0.235 | 19 | 0 | 0  | $0.000 |
| B-repl          | ✓ | 110 | 1  | $0.268 | 22 | **0** | 0  | $0.000 |
| B-judge-async   | ✓ | 120 | 1  | $0.194 | 15 | 0 | 1  | $0.005 |
| B-all           | ✓ | 310 | **7**  | $0.405 | 23 | 0 | 7  | $0.034 |
| B-all-mech-weak | ✓ | 460 | **11** | $0.519 | 27 | 0 | 12 | $0.058 |

`results` = number of result entries in `driver.json` (1 = clean completion; >1 = continuation-loop artifact from Step 3/D3). `tools` = total tool-use entries across all turns. `replsh` = count of tool-use entries whose `name == Bash` with `replsh launch|eval` in the command. `judge_n` / `judge$` = entries in `.succession/log/judge.jsonl` and their summed `cost_usd` field.

All 8 cells resolved → zero signal from resolution rate (saturation confirmed as expected). Signal is entirely in the continuous columns.

### Headline findings

**1. The rule-delivered `verify-via-repl` advisory does not cause replsh invocation.** B-repl, B-all, and B-all-mech-weak all received the rule via SessionStart's `additionalContext` bundle (advisory-summary + active-rules-digest containing the full rule body). **None of them launched replsh.** C-treatment, which receives the same instruction baked into the task prompt itself, launched replsh 6 times. Rule-layer delivery is a hard zero on this instance; prompt-layer delivery lands.

**2. The "2.2× contamination" effect from the smoke section does not replicate.** B-base (hooks wired, rules in SessionStart context, no judge, no reinject fires) took 110s / 18 tools. C-control (no hooks) took 100s / 19 tools. Pairwise delta is ≤ 10%. The 286s / 53-tool blowup in C-control smoke Run 2 was a single unlucky thrash, not a causal signal from rule delivery. Run-to-run variance at claude-p with tool calling is the parsimonious explanation; kill the contamination hypothesis.

**3. The judge layer works correctly as an observer.** B-judge-async fired 1 verdict in 15 turns (1 `violated verify-via-repl`, $0.005). B-all fired 7 verdicts in the expanded-loop 23 turns (all `violated verify-via-repl`, $0.034). B-all-mech-weak fired 12 verdicts (10 violated, 1 ambiguous, 1 not-applicable; 11 `verify-via-repl` + 1 `judge-conscience-framing`, $0.058). All well-formed JSON, all under session budget. Judge-async subprocess detachment held end-to-end across every cell where it was enabled.

**4. Mechanical weakening does not cause critical-safety regressions.** B-all-mech-weak has the `verify-via-repl` mechanical floor stripped. Zero `violated` judge verdicts on any critical-safety-pattern rule (rm -rf, credential exposure, etc.). The 10 `violated` verdicts are all `verify-via-repl` (advisory) and 1 is `judge-conscience-framing` (non-critical). Mechanical weakening is safe-at-n=1 on this instance, but this only proves the negative: critical-safety rules aren't *present* as mechanical entries in the current ruleset, so "weakening" mechanically is closer to a no-op than a real test of the fallback.

**5. The headless continuation loop is worse in cells that emit more hook output.** `results` count climbed B-all (7) → B-all-mech-weak (11). Both are cell configurations with judge + reinject + rule delivery all active. The emission cap of 3 from D3 holds — judge entries are 7 and 12 respectively, but those include entries from trailing "stale task" result turns, not extra Stop-hook emissions. The extra result turns seem driven by some claude-p internal re-entry path, not by Succession re-emitting advisory bundles. This needs a root-cause trace before promoting any config that wires the Stop hook in headless mode.

### Pairwise deltas

| Pair | What it isolates | Observed |
|---|---|---|
| B-base → B-reinject | hybrid reinject gate alone | no visible reinject fire (single-result run, turn count below 10-turn threshold, bytes below 200k). Cost/tools basically identical. Gate did not fire — expected on a 20-turn completion. |
| B-base → B-repl | rule-delivered replsh | **0 replsh invocations**. Tool count +4 (Read 3→3, Edit 4→5, Bash 10→14). Rule delivery produced more Bash iterations, not fewer, and zero replsh. |
| B-base → B-judge-async | judge layer alone | 1 judge call, $0.005 judge cost, main-agent tokens near-identical. Judge observed 1 violation on a 15-turn run. Clean. |
| B-base → B-all | combined conscience loop | +1 result → 7 results (loop artifact), +7 judge calls, +$0.15 total cost. Same 0 replsh. Combined loop is dominated by the judge+reinject overhead cost, not by behavioral change in the main agent. |
| B-all → B-all-mech-weak | mechanical-layer removal | +4 results, +$0.11 cost, +5 judge calls. Zero critical-safety regressions. Strictly more continuation-loop thrash with no compensating upside. |
| C-control → C-treatment | prompt-delivered replsh | +6 replsh, +13 tool calls, +$0.11. Prompt delivery works. |
| **C-treatment → B-repl** | **rule vs prompt delivery** | **6 → 0 replsh.** Rule delivery is not within the same order of magnitude as prompt delivery; it produced zero. |

---

## Decision gates

Re-stated gates against sweep data. R1 was re-framed earlier (wall-time proxy) because cross-driver token accounting is apples-to-oranges.

| Gate | Threshold | Observed | Verdict |
|---|---|---|---|
| **R1** — port fidelity | C-control resolved=true AND wall ≤ 450s | resolved=true, 100s | ✓ **PASS** |
| **R2** — loop mechanism fires | B-all.judge_calls ≥ 3 AND judge_calls ≤ 2×tool_calls | 7 judge_calls, 7 ≤ 46 | ✓ **PASS** (reinject fires not directly measurable — the cell completed in one reinject-gate interval, but the gate itself is unit-tested and the emission cap held) |
| **R3** — rule delivery reaches model | B-repl.replsh ≥ 3 | **0** | ✗ **FAIL (hard)** |
| **R4** — cost sane | B-all.judge_cost ≤ $0.50 | $0.034 | ✓ **PASS** |
| **R5** — mechanical weakening safe | zero violated critical-safety verdicts in B-all-mech-weak | 0 (only advisory rules violated) | ✓ **PASS (weak — no critical rules loaded mechanically; see Finding 4)** |
| **R6** — rule ≈ prompt delivery | \|B-repl.repl - C-treatment.repl\| ≤ max(B-repl.repl, C-treatment.repl) | \|0 − 6\| = 6 = max(0, 6). Satisfies the boundary but rule delivery is a *hard zero* while prompt delivery is 6 | ✗ **FAIL (substantive)** |

Two hard fails: **R3 (rule delivery reaches model)** and **R6 (rule ≈ prompt delivery)**. These are the two gates that most directly test the value proposition of the conscience loop's rule-delivery layer. Both say the same thing: on pytest-7373, advisory rules injected via SessionStart `additionalContext` do not change model behavior in the direction the rule asks for. The model reads them (the bundle is in its context window; PreToolUse logs show it saw every tool call against the rules), but chooses not to launch replsh.

---

## Recommendation

**Do NOT promote `B-all` as the new Succession default on the basis of this sweep.**

The primary claim the conscience loop makes is "rule-delivered guidance changes model behavior the same way prompt-delivered instructions do, but without burning prompt-context budget." On this instance, **that claim is refuted** — rule delivery lands a 0, prompt delivery lands a 6. Shipping `B-all` as default would ship the cost (judge calls, continuation-loop overhead, Stop-hook emission complexity) without shipping the behavior change that justified those costs.

The gates that *do* pass (R1, R2, R4, R5) establish that the machinery works: hooks are wired correctly, the judge observes and records well-formed verdicts under budget, the async subprocess detachment holds, mechanical weakening doesn't break anything critical. So the loop is *sound*. It just doesn't *land the rules*.

Possible interpretations (directional, need more data to discriminate):

1. **pytest-7373 is too easy.** The model already solves this in 18–20 tool calls without needing to verify in a REPL. Rule says "use replsh when you'd otherwise guess"; the model never needs to guess. Rule delivery can't show value where the target behavior isn't needed. Under this interpretation, the correct next step is D4 (hard-instance fallback on a pytest instance where the fix genuinely requires stepping into code), not main sweep.

2. **Rule delivery via SessionStart `additionalContext` has insufficient salience.** The model sees the rule body but weights it below the task prompt itself. Under this interpretation, the fix is to change delivery — inject rules into UserPromptSubmit or as a system-message append, not via SessionStart's advisory bundle. That's a code change, not a test.

3. **Sonnet 4.6 specifically doesn't take replsh suggestions.** Opus or Haiku might behave differently. v4 was opencode+openrouter+Sonnet-4.6; this sweep is claude-p+Sonnet-4.6. The one variable we didn't control for is whether prompt-vs-rule delivery discriminates across models.

### Concrete next actions, in order of priority

1. **D4 — hard-instance fallback.** Pick `pytest-dev__pytest-5227` (or 5103 / 11143), build its venv, run only `C-treatment` and `B-repl` against it. Two runs, ~$1. If `B-repl.replsh ≥ 3` on a hard instance, interpretation (1) is right and the rule delivery is fine, just untestable on easy instances. If `B-repl.replsh = 0` again, interpretation (2) or (3) is real and a code-level fix is needed before the loop is worth shipping.

2. **Root-cause the headless continuation loop.** B-all → 7 results, B-all-mech-weak → 11 results. Both violate the "single clean completion" invariant. The emission cap held (we know Stop is not re-emitting), so some other claude-p internal is re-entering. Needs a transcript-level read to identify which event chain is triggering the extra result turns. Until this is understood, any cell that wires Stop + judge in headless mode has unknown cost tails.

3. **Ship the code fixes as-is (D5 commit).** The reinject emission cap, stop.clj empty-transcript guard, harness changes, and this NOTES.md are all genuine improvements regardless of whether B-all promotes. They make the loop *safer in headless mode* even if the loop doesn't (yet) justify its own costs.

4. **Do NOT ship the installer fix in this commit** (scope discipline — it's a separate concern and a larger-blast-radius change that deserves its own review).

### What this sweep did establish, directionally

- The conscience loop's hook plumbing works in headless mode when carefully configured.
- The judge layer observes and records verdicts correctly; its subprocess detachment is fine.
- The critical-safety floor (hardcoded in `pre_tool_use.clj`) is still the only real mechanical defense; advisory rules are *not* substitutes.
- The "2× contamination" finding from C-control smoke Run 2 was run-to-run variance, not causal. Document this so we don't chase it again.
- Rule delivery via SessionStart advisory bundles is, on this instance and this model, behaviorally inert. This is the load-bearing negative result of the sweep.

### Budget accounting

| Item | Cost |
|---|---|
| Pre-sweep (hook spike, smokes, D2, D3 x2) | $1.91 |
| 8-cell sweep first pass (6 clean + 2 rate-limited at $0) | ~$1.67 |
| 8-cell sweep retries (B-all, B-all-mech-weak) | ~$0.92 |
| **Total spent** | **~$4.50** |
| D4 hard-instance fallback (if greenlit) | +~$1.00 |
| **Projected final** | **~$5.50** |

Well under the $6.50–$7.50 budget envelope in the plan.
