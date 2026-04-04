# Integration Test Suite for Babashka Hooks

## Context

The bb implementation has 48 unit tests / 290 assertions, all passing. But no test exercises a hook's `-main` end-to-end with real fixture files — they only test internal functions in isolation. The bash test suite (`tests/test_succession.sh`, 53 tests) does this with fixture dirs, mock `claude` binary, and JSON piping. We need the same for bb.

---

## Source change: Refactor pre_tool_use.clj

`pre_tool_use/-main` calls `(System/exit 0)` after printing a block decision (line 83). This kills the test runner. Fix: extract logic into a `run` function that returns the result, keep `-main` as a thin wrapper.

**Modify: `bb/src/succession/hooks/pre_tool_use.clj`**
- Extract body of `-main` into `(defn run [input] ...)` returning `{:decision "block" :reason "..."}` or `nil`
- `-main` becomes: parse stdin → call `run` → print result → `System/exit`
- Effectiveness logging stays inside `run` (it's a side effect we want in integration tests too)

---

## Test helpers

All in `bb/test/succession/integration_test.clj`:

```clojure
(defn create-test-fixture! []
  ;; Returns {:root, :home, :project, :global-rules, :project-rules, :compiled, :transcript-dir}
  )

(defn create-rule-file! [dir id enforcement body & {:as opts}]
  ;; Writes a rule .md with full YAML frontmatter
  )

(defn create-transcript! [dir filename entries]
  ;; Writes JSONL from [{:type "human" :content "..."} ...]
  )

(defn run-hook [hook-fn json-str]
  ;; Binds *in* to StringReader, captures stdout with with-out-str
  )

(defn with-home [home-path f]
  ;; Sets user.home, calls f, restores in finally
  )

(defn cleanup-state-files! [session-id]
  ;; Removes /tmp/.succession-{turns,extract-offset,correction-flag}-session-id
  )
```

Mock `call-claude` via `with-redefs` — dispatches on prompt content:
```clojure
(defn mock-call-claude [responses]
  (fn [prompt _model _timeout]
    (cond
      (str/includes? prompt "YES\" or \"NO\"") (:tier2 responses)
      (str/includes? prompt "violation of an existing rule") (:matching responses)
      (str/includes? prompt "behavioral pattern extraction") (:extraction responses)
      :else nil)))
```

---

## Tests (22 total)

### pre_tool_use (8 tests)
| Test | What it verifies |
|------|-----------------|
| block-bash-pattern | Write mechanical rule → resolve → feed matching Bash command → block |
| block-tool | Rule with `block_tool: Agent` → feed Agent → block |
| allow-non-matching | Same rules → feed Read → nil (allow) |
| no-rules-file | No compiled/tool-rules.json → nil |
| require-prior-read-block | Edit without prior Read in transcript → block |
| require-prior-read-pass | Edit after Read of same file → allow |
| global-fallback | Rules only in global dir → still blocks |
| meta-cognition-logging | After block → `meta-cognition.jsonl` has `rule_violated` |

### session_start (4 tests)
| Test | What it verifies |
|------|-----------------|
| full-flow | Advisory + mechanical rules → compiled artifacts exist, output has `ACTIVE RULES` |
| skill-loading | Skill in `.succession/skills/` → copied to `.claude/skills/` |
| empty-state | No rules/skills → output has resolution note, no crash |
| activity-logging | After run → `succession-activity.jsonl` has `session_start` event |

### stop (6 tests)
| Test | What it verifies |
|------|-----------------|
| correction-detection | Transcript with "don't do that" + mock YES → correction-flag-file created |
| correction-matching | Existing rule + mock returns rule-id → `rule_violated` in meta-cognition log, no flag file |
| advisory-reinjection | Turn counter at interval, advisory-summary.md exists → output has advisory content |
| turn-counter | Run twice → turn file shows "2" |
| extraction-pipeline | Large transcript + flag + mock extraction JSON → new rule file created, flag consumed |
| no-transcript | No transcript_path → no crash, no output |

### resolve round-trip (4 tests)
| Test | What it verifies |
|------|-----------------|
| project-wins | Global + project same ID → resolve → project version in compiled artifacts |
| explicit-overrides | Rule with `overrides: [other-id]` → other-id absent |
| disabled-filtered | Disabled rule → absent from all artifacts |
| mechanical-to-pretooluse | Mechanical rule → resolve → feed to pre_tool_use `run` → blocks |

---

## Verification

```bash
bb -cp bb/src:bb/test -e '(require (quote succession.integration-test)) (clojure.test/run-tests (quote succession.integration-test))'
```

All 22 integration tests pass + all 48 existing unit tests still pass.

---

## Files

| Action | Path |
|--------|------|
| **Modify** | `bb/src/succession/hooks/pre_tool_use.clj` — extract `run` from `-main` |
| **Create** | `bb/test/succession/integration_test.clj` — 22 integration tests |
