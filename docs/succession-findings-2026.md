# Succession — Findings from the Conscience-Loop Experiment (Apr 2026)

*Distillation of `experiments/08-succession-bench/04-conscience-loop` into the
load-bearing conclusions the experiment actually established. This doc is the
authoritative reference for what we learned; the raw experiment artifacts live
outside git (gitignored, present on-disk only).*

---

## 1. What we measured

Two passes on the same harness:

- **Pass 1 — 8-cell signal sweep on pytest-dev/pytest-7373 (n=1/cell).** Tests
  the proposed "conscience loop" Succession default (`B-all`): hybrid reinject
  + async LLM judge + `verify-via-repl` rule + judge retrospectives reinjected
  into the bundle. Driver: `claude -p --model claude-sonnet-4-6
  --permission-mode bypassPermissions`.
- **Pass 2 — D-cell attention-drift follow-up on pytest-dev/pytest-5103 (n=1/cell).**
  Two cells (`D-claudemd-only` vs `D-claudemd-plus-refresh`) isolating the
  delivery-channel variable. No judge, no reinject, no Succession code body —
  a standalone babashka PostToolUse hook (`postref.bb`) vs plain CLAUDE.md.

### Pass 1 cell summary

| cell | resolved | wall_s | results | cost_usd | tools | replsh | judge_n |
|---|---|---|---|---|---|---|---|
| C-control       | ✓ | 100 | 1  | $0.301 | 19 | 0 | 0  |
| C-treatment     | ✓ | 150 | 1  | $0.415 | 32 | **6** | 0  |
| B-base          | ✓ | 110 | 1  | $0.252 | 18 | 0 | 0  |
| B-reinject      | ✓ | 130 | 1  | $0.235 | 19 | 0 | 0  |
| B-repl          | ✓ | 110 | 1  | $0.268 | 22 | **0** | 0  |
| B-judge-async   | ✓ | 120 | 1  | $0.194 | 15 | 0 | 1  |
| B-all           | ✓ | 310 | **7**  | $0.405 | 23 | 0 | 7  |
| B-all-mech-weak | ✓ | 460 | **11** | $0.519 | 27 | 0 | 12 |

All 8 resolved — pytest-7373 is **saturated**. Resolution rate carries zero
signal; discrimination lives entirely in the continuous columns. The striking
row is `B-repl` vs `C-treatment`: both deliver a "use `replsh eval` to verify"
instruction, but via different channels — `B-repl` via Succession's SessionStart
`additionalContext` advisory bundle, `C-treatment` via the task prompt directly.
Prompt delivery produced 6 replsh invocations; rule delivery produced 0. The
`results > 1` counts on `B-all` and `B-all-mech-weak` are the headless
continuation-loop artifact discussed in Finding 3.

### Pass 2 cell summary (pytest-5103)

| | D-claudemd-only | D-claudemd-plus-refresh | delta |
|---|---|---|---|
| tool_calls_total | 66 | 97 | +31 |
| cost_usd | $2.22 | $4.32 | +$2.09 |
| duration_s | 688 | 1469 | +781 |
| **productive `replsh eval` calls** | **0** | **18** | **+18** |
| `replsh launch` attempts | 1 | 12 | +11 |
| refresh emissions fired | — | 5 (cap hit) | — |
| patch lines | 165 | 236 | +71 |

Same model, same prompt, same driver, same instance. The only variable is
whether PostToolUse refresh is wired. `D-claudemd-only` is CLAUDE.md-at-position-0
delivery; `D-claudemd-plus-refresh` adds a ~400-byte PostToolUse reminder fired
on the 5th matching tool call and then every 5 calls or 40 KB of transcript
growth, whichever comes first. **18 productive replsh calls vs 0.**

---

## 2. Finding 1 — Adjacent-to-now refresh beats front-of-context delivery

**Claim.** On long, hard sessions where a rule must survive the integration
phase, delivering the rule *adjacent to the current frame* (via PostToolUse
`hookSpecificOutput.additionalContext`) dominates delivering it at the front
of context (SessionStart `additionalContext`, or even CLAUDE.md's
re-prepend-on-every-call).

**Evidence.** `D-claudemd-plus-refresh` produced **18 productive `replsh eval`
calls** on pytest-5103; `D-claudemd-only` produced **0**. The sign of the
effect is unambiguous even at n=1 — the primary signal is an 18-0 split, not
a marginal distribution difference. Plan's directional threshold was ≥3.

**Mechanism — `reorderAttachmentsForAPI`.** Claude Code's internal attachment
reorderer bubbles PostToolUse `hookSpecificOutput.additionalContext` to land
immediately after the most recent `tool_result` and before the next assistant
turn. This places the text inside the model's current attention window rather
than at position 0 where attention has decayed as context grew. SessionStart
`additionalContext` lands once in message history and drifts backwards
exactly like the failure mode the hypothesis is designed to counter.
CLAUDE.md's `prependUserContext` re-prepends to position 0 on every call —
better than SessionStart, but still competing with the growing tail.

**Two-phase adoption model.** The `D-claudemd-only` cell did *not* fail to
attempt the rule — both cells attempted `replsh launch` at matched call #2.
The failure was in the **integration** phase: Cell A ran one failed launch,
bounced off the friction (unclear launch syntax), and abandoned replsh for
the remaining 60+ tool calls. Cell B hit the same friction, but the refresh
firing at matched calls 5/10/15/20/24 sustained the rule through the
failed-launch zone. At call 23-24 — coincident with the fifth refresh
hitting the cap — the model figured out the correct `replsh eval --name
swebench '<expr>'` pattern and sustained **17 consecutive productive replsh
invocations** doing genuine AST exploration of pytest's assertion rewriter.
After the cap hit, the model kept the habit for another ~15 turns without
reinforcement.

| phase | definition | Cell A | Cell B |
|---|---|---|---|
| **Trigger** | matched calls until first attempt | 2 | 2 |
| **Integration gap** | matched calls from first attempt to first productive use | ∞ | 22 |
| **Sustainment** | productive uses after last refresh | 0 | 17 |

The refresh's load-bearing contribution was *sustaining salience across the
friction zone* until the tool integrated into the working loop. Once
integrated, sustainment was free.

**Cap=5 caveat.** The experiment ran with a hard cap of 5 emissions per
session. Integration happened *exactly* at refresh #5 (matched call #24).
Had integration happened at matched call #40, Cell B would have been
unreinforced through 15 calls of the friction zone — potentially producing
the same failure mode as Cell A. The cap was a conservative safety rail
designed against a Stop-loop runaway that doesn't apply to PostToolUse
(which lands in `toolResults`, not Stop re-entry). Safe to remove or raise
significantly.

**Correction to the prior sweep's framing.** The Pass 1 sweep concluded
"SessionStart additionalContext rule delivery is inert" based on `B-repl`
= 0 replsh vs `C-treatment` = 6 replsh. That conclusion is correct **about
the specific channel SessionStart additionalContext** — that channel lands
once and drifts exactly like the failure mode we're trying to counter. It
was wrong to generalize. CLAUDE.md delivery is not inert (Cell A did
attempt). PostToolUse refresh delivery is positively the strongest channel
tested. SessionStart additionalContext is inert because it *doesn't
re-prepend and doesn't land adjacent*.

---

## 3. Finding 2 — LLM judges are affordable but currently observers

**Claim.** An LLM judge running per tool use at moderate sampling is well
under budget at Sonnet-4.6 prices ($0.0027/verdict), but in the sessions
tested the judge acted purely as an observer — its verdicts were never
reinjected back into the main agent's context, because the reinject gate
that would carry them fires on a byte/turn threshold that no tested session
crossed.

**Evidence.** The hook-unit spike measured **$0.0027 per sync Sonnet verdict**
with ~18 s latency. Across the full sweep, judge costs were:

| cell | judge_n | judge$ |
|---|---|---|
| B-judge-async | 1 | $0.005 |
| B-all | 7 | $0.034 |
| B-all-mech-weak | 12 | $0.058 |

A 20-tool-call session with 50% sampling costs ~$0.03 — three orders of
magnitude below the session budget cap ($0.50). All verdicts were well-formed
JSON with correct retrospectives. Subprocess detachment (async mode) worked
end-to-end across every cell where it was enabled: parent hook returned in
~342 ms, child survived and wrote the verdict later.

**But the feedback loop never closed.** Retrospectives are written to
`.succession/log/judge.jsonl` and read by the reinject gate, which bundles
them with `advisory-summary.md` and `active-rules-digest.md` and emits the
bundle as `additionalContext`. The gate fires when `bytes-grown ≥ 200 KB`
**or** `turns-grown ≥ 10`, whichever first. In every sweep cell, the single
completion finished below both thresholds — the reinject gate **never fired**
inside a single result turn. The judge wrote verdicts; nothing in the main
agent's context ever read them.

**Implication — the judge is cheap enough to do more.** At $0.0027/verdict
and ~$0.03/session at default sampling, the judge budget is not the
constraint on how much the judge does. The constraint is the delivery path:
what the judge observes has no way to reach the main agent's next turn
unless the reinject gate fires, and on typical sessions the gate doesn't
fire. Two plausible feedback channels the budget would support:

1. **Lower-threshold reinject** — fire the judge-retrospective bundle on a
   sub-gate that doesn't wait for 200 KB / 10 turns. The risk is the
   headless continuation loop (see Finding 3) which the current cap was
   designed to bound.
2. **Rolling 4-category memory compaction** — the judge produces rolling
   summaries along Succession's four knowledge categories (strategy /
   failure-inheritance / relational-calibration / meta-cognition), written
   as cards under `.succession/memory/<category>/` and fed back into refresh
   text or adjacent context. This is a distinct channel from the reinject
   bundle, and it would fire at refresh cadence (every N matched tool calls)
   rather than byte/turn milestones. Phase 2 scope.

Neither is a Phase 1 deliverable. The finding that justifies adding them is
"the judge is cheap enough to carry more feedback; the bottleneck is a
delivery channel that doesn't wait for milestones the typical session
doesn't cross."

---

## 4. Finding 3 — Mechanical blocks are redundant with the harness

**Claim.** The mechanical enforcement tier (`pre_tool_use.clj` +
`tool-rules.json` regex blocks + hardcoded critical-safety floor) is
redundant with Claude Code's permission system and harness-level guards.
It was not load-bearing on any cell, and keeping it imposes complexity,
test surface, and an emission asymmetry that contributes to the headless
continuation loop.

**Evidence (weak but consistent).** Two angles:

1. **B-all-mech-weak runs without the `verify-via-repl` mechanical floor**
   and produces zero `violated` verdicts on any critical-safety-pattern
   rule. The 10 `violated` verdicts are all `verify-via-repl` (advisory).
   This is only a *weak* negative — the critical-safety floor in
   `pre_tool_use.clj:32-47` is hardcoded regex on `rm -rf`, `git push
   --force`, destructive SQL, credential writes, and none of those
   patterns appeared organically in a pytest bug-fix session. The test
   shows "no regression from weakening mechanical," but the mechanical
   layer was never the thing being tested.
2. **The conceptual argument.** Claude Code's built-in permission system
   (`settings.json` permission rules, the `--permission-mode` flag, the
   `allowed-tools` and `disallowed-tools` scopes) already covers the
   critical-safety surface. A user wanting to block `rm -rf /` can add a
   deny rule in `.claude/settings.json` without going through Succession's
   compiled `tool-rules.json`. Succession's mechanical tier is a
   redundant layer doing the same job less well (regex only, no
   user-facing review, no integration with permission prompts).

**It was never the primary win.** The Pass 1 sweep was designed around
rule-delivery-changes-behavior as the primary claim; the mechanical tier
was the *fallback* for when that claim failed. The fallback was never
exercised, and even if it had been, the argument above says it should live
in Claude Code, not Succession.

**Migration path.** Projects that relied on Succession's critical-safety
floor should move those patterns to Claude Code `settings.json` permission
deny lists. The patterns to migrate are:

- `rm -rf /` / `rm -rf $HOME` — irreversible filesystem wipe
- `git push --force` to `main|master|prod|production|release` —
  destructive to shared history
- Unconstrained destructive SQL (`DROP TABLE`, `TRUNCATE`, `DELETE FROM …;`
  with no WHERE)
- Credential write/exfil patterns (API keys, private keys)

Concrete migration for `rm -rf /`:

```json
{
  "permissions": {
    "deny": [
      "Bash(rm -rf /*)",
      "Bash(rm -rf ~*)",
      "Bash(git push*--force*main)"
    ]
  }
}
```

The regex-to-permission-rule translation is not 1:1 — Claude Code
permission rules are glob, not regex — but the critical patterns above are
short enough to translate directly. Document this in the Phase 2 removal
commit as the migration note.

**Related: the headless continuation loop.** On `B-all` and
`B-all-mech-weak`, `driver.json` contained **7 and 11 `result` entries**
instead of 1. Root cause partially traced in Pass 1: the Stop hook's
reinject phase emits an advisory bundle via `hookSpecificOutput.additionalContext`;
in headless `claude -p` mode there is no human to read the advisory and
decide whether to respond, so Claude Code treats it as user input and the
model generates a reply. That reply triggers another Stop hook. The
`reinject.clj` emission cap (3 per session) halved the cost but did not
eliminate it — some other claude-p internal re-entry path is still firing.
Any Phase 2 change wiring Stop in headless mode has unknown cost tails
until this is understood. Not resolved here; flagged in §7.

---

## 5. Finding 4 — replsh grounding is the target behavior, not the delivery mechanism

**Claim.** `replsh` (the REPL-grounded verification tool) is what the rule
is *asking for* — "verify with replsh eval instead of guessing" — and it's
the right behavior for hard AST-level work. Succession should nudge replsh
use through refresh text and skills, not re-implement grounding.

**Evidence.**

- **C-treatment (prompt delivery) → 6 replsh invocations. C-control → 0.**
  Prompt-level instructions do move behavior; prompt delivery is not the
  bottleneck.
- **B-repl (rule delivery) → 0 replsh invocations.** Same rule content, but
  delivered via SessionStart advisory bundle. The bundle is in context; the
  PreToolUse log shows the model saw every tool call against the rules. But
  0 replsh. This is the "wrong channel" finding now corrected by Finding 1.
- **D-claudemd-plus-refresh (adjacent-to-now delivery) → 18 productive
  `replsh eval` calls.** The refresh text explicitly names the invocation
  pattern (`replsh eval --name swebench '<expr>'`), and the model sustained
  17 consecutive productive uses once it figured out the launch syntax.

**Confound — refresh text content.** The refresh reminder explicitly
includes the concrete launch and eval invocation. A reminder that only
said "verify your assumptions" might produce less uplift. This experiment
does not separate "refresh increases rule salience" from "refresh teaches
tool syntax by example." The ablation is a Phase 2 follow-up.

**Implication.** Succession should not re-implement grounding. replsh
exists, it works, it's at `~/Projects/g-daniel/replsh`. What Succession
should do is (a) nudge its use through refresh text, (b) ship a replsh
skill that teaches the launch/eval pattern and installs the config, (c)
pre-launch a session where possible to shortcut the integration gap. The
initial-friction problem (both D-cells spent early matched calls figuring
out `replsh launch` syntax) is a tooling-documentation problem orthogonal
to the drift hypothesis — fixing it would shorten the integration gap on
every future run.

---

## 6. What the findings imply for Succession

Mapped onto the current `bb/src/succession/` layers:

| Layer | Keep / drop / promote | Rationale |
|---|---|---|
| **SessionStart: rule digest + advisory bundle** | Keep (as orientation, not as rule delivery) | It still gives the model an initial picture of what rules exist. Don't expect behavioral uplift from it — that's what adjacent-to-now delivery is for. |
| **SessionStart: skill copy** | Keep | Skills are injected differently (file references, not `additionalContext`); not covered by the drift finding. |
| **PreToolUse: mechanical tier** | **Drop in Phase 2** | Redundant with Claude Code permission system. Migration path documented in Finding 3. |
| **UserPromptSubmit: classifier + top-N rule injection** | Keep (opt-in, judge.enabled) | Injection at UserPromptSubmit lands close to the frame the user just asked about — structurally similar to adjacent-to-now. Untested in this experiment; not ruled out. |
| **PostToolUse: judge** | Keep (opt-in) + promote | Cost-bounded, well-formed, subprocess detachment works. Bottleneck is the delivery channel for its output. Phase 2 should add a lower-latency feedback path. |
| **PostToolUse: reinject (heavy bundle)** | Keep as the *occasional* channel | Fires on byte/turn milestones; intended as full context rebuild, not continuous reinforcement. Emission cap stays until the headless continuation loop is root-caused. |
| **PostToolUse: refresh (short reminder)** | **Promote to default for hard sessions** | The 18-0 result is the cleanest positive finding of the experiment. Needs replication on a second hard instance before it's load-bearing. |
| **Stop: correction detection + extraction** | Keep | Orthogonal to drift; detects user corrections and adds rules. Unchanged by the findings. |
| **Stop: reinject + turn judge** | Keep with caveat | The headless continuation loop concerns Stop specifically. Any change here needs the root-cause trace first. |

This mapping is the bridge into Phase 2, not a commitment to any specific
code-level change. Phase 2 will be planned separately against the audit at
the end of the Phase 1 plan document.

---

## 7. Open questions

1. **Headless Stop-hook continuation loop.** `B-all` produced 7 result
   entries, `B-all-mech-weak` produced 11. The `reinject.clj` emission cap
   held — Stop is not re-emitting — but something else is re-entering the
   session. Each subsequent result sees a fresh `system: init` event with
   the same session_id, so `claude -p` is re-initializing the session
   inside one invocation for reasons not yet traced. Any Phase 2 change
   wiring Stop in headless mode has unknown cost tails until this is
   understood.
2. **Refresh text sourcing is per-project only.** Current precedence:
   inline `:text` > `:textFile` (absolute) > `$cwd/.succession/refresh-text.md`
   > `$cwd/.succession/compiled/refresh-text.md`. No global default from
   `~/.succession/`, no auto-compilation from rules. Every project that
   wants refresh has to drop its own `refresh-text.md`. A sensible default
   would be to compile refresh text from the highest-priority advisory
   rule's body at SessionStart → `.succession/compiled/refresh-text.md`.
3. **Judge correctness is not ground-truthed.** Verdicts are well-formed
   JSON and cost-bounded, but "was this verdict actually correct about
   the rule" was not measured. In the one sweep where retrospectives
   could have fed back through reinject, the reinject gate never fired,
   so downstream correctness was confounded from the start.
4. **Refresh layer has n=1 validation.** Confirmed directionally on
   pytest-5103 only. pytest-11143 is the obvious next instance. Without
   replication on a second hard instance, "refresh reliably sustains
   rule adherence" is a hypothesis, not a reliable finding.
5. **Refresh text content confound.** The 18-0 split could be "refresh
   sustains rule salience" or "refresh teaches tool syntax by example."
   Ablation needed: concrete-invocation template vs generic
   "verify assumptions" on the same instance.
6. **Cost asymmetry.** Cell B cost +$2.09 and ran +31 tool calls over
   Cell A. Refresh pushes the model into more exhaustive verification,
   which is expensive. Net quality uplift vs verification theater is
   unmeasured — neither D-cell patch was run through the SWE-bench
   evaluator, so correctness-per-dollar is an unknown.
7. **Installer is actively broken.** `scripts/succession-init.sh` uses
   `.hooks = $hooks` (jq replace) instead of merge — destroys existing
   hooks when run on a project with other hook wiring. Also omits
   PostToolUse and UserPromptSubmit registration. Separate concern from
   this experiment; flagged as a known liability for the project.

---

## 8. Code map (appendix — replaces `docs/succession-layers.md`)

Current contents of `bb/src/succession/` at the time of this doc.
One-line descriptions, for orientation while reading the audit in the
Phase 1 plan document.

```
bb/src/succession/
  core.clj                ; CLI entry point: resolve | effectiveness | extract | skill-extract
  config.clj              ; central config loading, defaults, model-ids map (haiku forbidden for judge)
  resolve.clj             ; rule files → compiled artifacts (mechanical | semantic | advisory)
  yaml.clj                ; rule file parse/emit (YAML frontmatter + markdown body)
  transcript.clj          ; transcript JSONL read, turn counting, latest lookup
  activity.clj            ; JSONL activity log append + rotation
  effectiveness.clj       ; rule effectiveness counter update
  extract.clj             ; CLI pattern extraction — transcript → new rule files
  skill.clj               ; CLI skill extraction — transcript → SKILL.md

  judge.clj               ; LLM judge: prompt build, verdict parse, subprocess invocation,
                          ; session budget tracking, haiku validation at config-load time
  reinject.clj            ; heavy bundle gate (bytes-or-turns), fire-count cap,
                          ; shared emit-count cap with Stop (headless-loop workaround)
  refresh.clj             ; [NEW Apr 2026] adjacent-to-now refresh gate — this is
                          ; the code backing Finding 1. Short reminder emitted on tool-call
                          ; interval or byte growth, loaded from per-project refresh-text.md

  hooks/
    common.clj            ; require-not-judge-subprocess! recursion guard
    session_start.clj     ; resolve-and-compile + inject advisory digest + copy skills
    pre_tool_use.clj      ; mechanical floor + tool-rules.json blocks  [DROP Phase 2]
    post_tool_use.clj     ; judge + reinject + refresh orchestration (emits combined
                          ; additionalContext as "<refresh>\n\n<reinject>")
    user_prompt_submit.clj; classifier pass + top-4 rule injection (judge.enabled required)
    stop.clj              ; correction detection + extraction + turn judge + reinject
                          ; (the place the headless continuation loop lives)
```

For the full audit — data shapes, logic layers, data flow, known issues —
see §C of the Phase 1 plan document: it's the ground-truth reference
material Phase 2 will work from.
