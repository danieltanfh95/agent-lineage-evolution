# Succession — Rename + Knowledge Categories + Babashka Migration

## Context

Imprint v2 was just built and committed (ec482d0). Three improvements identified from a side conversation and the succession research brief:

1. **Rename Imprint → Succession** — closer to the ALE framework's core concept of generational behavioral inheritance
2. **Four knowledge categories** — the research brief (succession-research-brief-v2.md) identifies strategy, failure inheritance, relational calibration, and meta-cognition as distinct knowledge types that memory systems miss. These should become first-class rule categories with effectiveness tracking.
3. **Migrate to Babashka** — bash+jq is fragile for YAML parsing and JSON manipulation. Babashka gives us Clojure (proper data structures) + Datascript (in-process Datalog for rule resolution and effectiveness tracking) + ~10ms startup time.

---

## Phased Approach

Do rename + categories in bash first (testable immediately), then migrate to babashka with the final schema already defined. The bash version becomes the reference implementation and test oracle for the bb rewrite.

---

## Phase 1: Rename Imprint → Succession

### File renames (git mv)

| From | To |
|------|-----|
| `scripts/imprint-resolve.sh` | `scripts/succession-resolve.sh` |
| `scripts/imprint-pre-tool-use.sh` | `scripts/succession-pre-tool-use.sh` |
| `scripts/imprint-stop.sh` | `scripts/succession-stop.sh` |
| `scripts/imprint-session-start.sh` | `scripts/succession-session-start.sh` |
| `scripts/imprint-init.sh` | `scripts/succession-init.sh` |
| `scripts/imprint-extract-cli.sh` | `scripts/succession-extract-cli.sh` |
| `scripts/imprint-skill-extract.sh` | `scripts/succession-skill-extract.sh` |
| `tests/test_imprint.sh` | `tests/test_succession.sh` |
| `docs/imprint-architecture.md` | `docs/succession-architecture.md` |

### Content replacements (all files)

| Pattern | Replacement |
|---------|-------------|
| `~/.imprint` | `~/.succession` |
| `.imprint/` | `.succession/` |
| `IMPRINT_GLOBAL_DIR` | `SUCCESSION_GLOBAL_DIR` |
| `IMPRINT_PROJECT_DIR` | `SUCCESSION_PROJECT_DIR` |
| `imprint-activity.jsonl` | `succession-activity.jsonl` |
| `/imprint ` | `/succession ` |
| `Imprint` (capitalized) | `Succession` |
| `imprint` (lowercase prose) | `succession` |
| `log_imprint_event` | `log_succession_event` |
| `.imprint-turns-` | `.succession-turns-` |
| `.imprint-extract-offset-` | `.succession-extract-offset-` |
| `.imprint-correction-flag-` | `.succession-correction-flag-` |

### Verification
- Run test suite after rename — all 33 tests must pass
- `grep -r "imprint" scripts/ tests/ docs/ README.md` should return zero hits

---

## Phase 2: Four Knowledge Categories + Effectiveness Tracking

### Extended rule schema

```yaml
---
id: no-force-push
scope: global
enforcement: mechanical
category: failure-inheritance    # NEW — strategy | failure-inheritance | relational-calibration | meta-cognition
type: correction                 # KEPT — how it was discovered (correction | confirmation | preference)
source:
  session: abc-123
  timestamp: 2026-04-01T10:00:00Z
  evidence: "User said: never force push"
overrides: []
enabled: true
effectiveness:                   # NEW — meta-cognition tracking
  times_followed: 0
  times_violated: 0
  times_overridden: 0
  last_evaluated: null
---
```

**Category definitions** (from the research brief):
- `strategy` — How the agent approaches problems (workflow patterns, methodologies)
- `failure-inheritance` — Patterns of failure to avoid (anti-patterns, things that went wrong)
- `relational-calibration` — Communication style adaptation (tone, verbosity, explanation depth)
- `meta-cognition` — Which heuristics proved reliable vs. which sounded plausible but failed

### Changes needed

1. **`lib.sh`** — extend `parse_rule_frontmatter` to handle `category:` and `effectiveness:` nested block (same pattern as existing `source:` handling)

2. **`succession-stop.sh`** — update extraction prompt to classify rules into four categories. Update rule file writing to include `category:` and `effectiveness:` fields.

3. **`succession-extract-cli.sh`** — same extraction prompt update

4. **`succession-pre-tool-use.sh`** — after blocking checks (or at end if no block), append effectiveness event to `~/.succession/log/effectiveness.jsonl`:
   ```json
   {"timestamp":"...","rule_id":"no-force-push","outcome":"violated","session":"..."}
   ```
   Must stay < 100ms — single JSONL append is O(1).

4b. **`succession-stop.sh`** — **semantic violation matching**: when a correction is detected (Tier 2 confirms YES), before creating a new rule, Sonnet checks if the correction matches an existing rule. If yes: increment `times_violated` on the existing rule (no new rule created). If no: create new rule as before. This reuses the existing Tier 2 Sonnet call — the match check is an additional ~$0.005 per correction.

   Matching prompt: "Given this user correction: '...' and these existing rules: [...], does this correction indicate a violation of an existing rule? If yes, return the rule ID. If no, return null."

5. **`succession-resolve.sh`** — after cascade resolution, add effectiveness analysis:
   - Rules with >50% violation rate (over 10+ evaluations) → flag for review
   - Rules with >80% follow rate + advisory tier → candidate for promotion to semantic
   - Output `review-candidates.json` in compiled dir

6. **`lib.sh`** — add `update_effectiveness_counters()` function that reads effectiveness.jsonl, groups by rule_id, updates frontmatter counters in rule files. Called by stop hook periodically.

7. **`SKILL.md`** — add `/succession effectiveness` command

8. **Tests** — new tests for category parsing, effectiveness tracking, promotion/demotion

### Append-only meta-cognition log

All rule lifecycle events are recorded in `~/.succession/log/meta-cognition.jsonl` — a complete audit trail of the rule system's evolution:

```jsonl
{"ts":"...","event":"rule_created","rule_id":"no-force-push","category":"failure-inheritance","source":"extraction","session":"abc"}
{"ts":"...","event":"rule_violated","rule_id":"no-force-push","session":"abc","context":"git push --force","detected_by":"pre-tool-use"}
{"ts":"...","event":"correction_matched","rule_id":"no-force-push","session":"abc","user_msg":"don't force push!"}
{"ts":"...","event":"rule_followed","rule_id":"no-force-push","session":"abc","detected_by":"pre-tool-use"}
{"ts":"...","event":"rule_promoted","rule_id":"no-force-push","from":"advisory","to":"semantic","reason":"80% follow rate"}
{"ts":"...","event":"rule_disabled","rule_id":"old-rule","by":"user"}
{"ts":"...","event":"session_summary","session":"abc","total_rules":15,"violations":3,"follow_rate":0.80}
```

This log serves multiple purposes:
- **Effectiveness counters** are materialized views of this log (computed periodically, written to rule frontmatter)
- **Model quality tracking** — plot rule compliance rates per session to detect regressions from model updates
- **Category analysis** — which knowledge categories (strategy vs failure-inheritance vs relational-calibration vs meta-cognition) are most/least effective?
- **Retrospective analysis** — feed into `succession extract` to understand behavioral patterns across sessions
- **In Phase 3**: loaded as Datascript facts for rich Datalog queries

### Backwards compatibility
- Rules without `category:` default to `"strategy"`
- Rules without `effectiveness:` default to all-zero counters

---

## Phase 3: Migrate to Babashka

### Why babashka

- **Datascript** (in-process Datalog) — cascade resolution becomes a 15-line Datalog query instead of 50 lines of jq. Effectiveness analysis, cross-rule inference, and skill-rule relationships become trivial queries.
- **Proper data structures** — no more fragile YAML parsing with awk/sed/bash string manipulation
- **~10ms startup** — comparable to bash, well within the 100ms budget for PreToolUse hooks
- **Built-in deps** — babashka ships with Datascript, Cheshire (JSON), and clojure.spec

### Project structure

```
bb/
  bb.edn                          # Project config (paths, no external deps needed)
  src/succession/
    core.clj                      # CLI dispatch entry point
    db.clj                        # Datascript schema + load-from-files + queries
    resolve.clj                   # Cascade resolution via Datalog
    yaml.clj                      # Rule file I/O (YAML frontmatter ↔ Clojure maps)
    effectiveness.clj             # Meta-cognition tracking + analysis
    extract.clj                   # Extraction prompt building + result parsing
    skill.clj                     # Skill extraction
    hooks/
      pre_tool_use.clj            # Mechanical enforcement
      session_start.clj           # Resolve + inject
      stop.clj                    # Correction detection + extraction + re-injection
  test/succession/
    db_test.clj
    resolve_test.clj
    hooks_test.clj
```

### Datascript schema

```clojure
(def schema
  {:rule/id              {:db/unique :db.unique/identity}
   :rule/scope           {}  ; :global | :project
   :rule/enforcement     {}  ; :mechanical | :semantic | :advisory
   :rule/category        {}  ; :strategy | :failure-inheritance | :relational-calibration | :meta-cognition
   :rule/type            {}  ; :correction | :confirmation | :preference
   :rule/enabled         {}
   :rule/body            {}
   :rule/file            {}
   :rule/overrides       {:db/cardinality :db.cardinality/many :db/valueType :db.type/ref}
   :rule/source-session  {}
   :rule/source-timestamp {}
   :rule/source-evidence {}
   :rule/directives      {:db/cardinality :db.cardinality/many :db/valueType :db.type/ref}
   :rule/times-followed  {}
   :rule/times-violated  {}
   :rule/times-overridden {}
   
   :directive/type       {}  ; :block-bash-pattern | :block-tool | :require-prior-read
   :directive/pattern    {}
   :directive/reason     {}
   
   :skill/name           {:db/unique :db.unique/identity}
   :skill/scope          {}
   :skill/description    {}
   :skill/trigger        {}
   :skill/steps          {:db/cardinality :db.cardinality/many}
   :skill/knowledge      {:db/cardinality :db.cardinality/many}
   :skill/rules          {:db/cardinality :db.cardinality/many :db/valueType :db.type/ref}})
```

### Key design decisions

- **Rule files remain source of truth** — Datascript DB is rebuilt from files on each `resolve`, not persisted. Users edit rules in any text editor, git diffs are clean markdown.
- **Effectiveness counters live in rule file frontmatter** — written back periodically by the stop hook. The JSONL log is the append-only event stream; counters are the materialized view.
- **EDN where possible, JSON/YAML where required**:
  - Rule files: **YAML frontmatter + markdown** (human-edited, universal convention)
  - Compiled artifacts: **EDN** (machine-generated, native to bb, no serialization overhead)
  - Config: **EDN** (`config.edn` instead of `config.json`)
  - Hook stdin/stdout: **JSON** (Claude Code's hook interface, non-negotiable)
  - Meta-cognition log: **JSONL** (tooling-friendly — jq, grep — queryable outside bb)
  - Datascript DB snapshot (if persisted): **EDN** (native serialization)
- **Bash fallback** — init script detects `bb` availability. Without it, bash scripts are used. With it, bb scripts are registered as hooks.
- **Hook interface unchanged** — JSON on stdin, JSON on stdout, exit 0/2. Babashka scripts are just a different implementation of the same contract.

### Babashka installation

Use babashka's official install script in `succession-init.sh`:
```bash
if ! command -v bb &>/dev/null; then
  echo "Installing babashka..."
  bash < <(curl -s https://raw.githubusercontent.com/babashka/babashka/master/install)
fi
```
This installs bb locally. Document in README as a prerequisite step.

### Coexistence during migration

```bash
# In succession-init.sh
if command -v bb &>/dev/null; then
  HOOK_CMD="bb ${HOOKS_DIR}/succession-pre-tool-use.bb"
else
  HOOK_CMD="${HOOKS_DIR}/succession-pre-tool-use.sh"
fi
```

---

## Verification

### Phase 1
- All 33 existing tests pass after rename
- `grep -ri "imprint" scripts/ tests/ docs/ README.md` returns zero

### Phase 2
- New tests for category parsing, effectiveness tracking, promotion logic
- Backwards compat: old rule files without category/effectiveness still load
- Effectiveness logging in PreToolUse stays < 100ms

### Phase 3
- **Pure function tests**: cascade resolution, YAML parsing, effectiveness analysis are all pure functions (data → data) — tested with `clojure.test`, no temp dirs or mock binaries needed
- **Property-based tests**: rule file round-tripping (parse → write → parse = identity) via `clojure.spec` generators
- **Data-driven test cases**: same test data shared between bash and bb suites to verify identical outputs
- **Datascript query tests**: cascade resolution, effectiveness queries, cross-rule inference
- **Meta-cognition log tests**: verify events are written, counters materialized correctly, category analysis queries
- **Benchmark**: mechanical hook < 100ms, session-start < 200ms
- **Integration**: run bash tests and bb tests against same rule files, verify identical compiled artifacts

---

## Files to modify

### Phase 1 (rename only — no logic changes)
- All files in `scripts/`, `tests/`, `docs/`, `README.md`

### Phase 2 (extend schema + add tracking)
- `scripts/lib.sh` — parse `category:` and `effectiveness:`, add `update_effectiveness_counters()`
- `scripts/succession-stop.sh` — extraction prompt, rule writing, effectiveness batch update
- `scripts/succession-extract-cli.sh` — extraction prompt
- `scripts/succession-pre-tool-use.sh` — effectiveness event logging
- `scripts/succession-resolve.sh` — effectiveness analysis after cascade
- `scripts/SKILL.md` — `/succession effectiveness` command
- `tests/test_succession.sh` — new tests

### Phase 3 (babashka rewrite)
- New `bb/` directory with all files listed above
- `scripts/succession-init.sh` — bb detection and hook registration
- `README.md` — babashka install instructions
- `docs/succession-architecture.md` — updated architecture
