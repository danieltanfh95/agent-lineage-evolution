# Remove dead bash scripts + SOUL framework

## Context

The bash→bb migration is complete. All 7 core succession scripts in `scripts/` have bb replacements. The bash test suite is superseded by 66 bb tests. The SOUL framework (`soul-init.sh` + `skills/soul/scripts/`) is an older/parallel framework whose hooks were never installed (`.soul/hooks/` is empty). Time to clean house.

---

## What to delete

### Dead succession bash scripts (replaced by bb)

| File | Replaced by |
|------|-------------|
| `scripts/lib.sh` | `bb/src/succession/{yaml,resolve,activity,effectiveness}.clj` |
| `scripts/succession-resolve.sh` | `bb/src/succession/resolve.clj` |
| `scripts/succession-session-start.sh` | `bb/src/succession/hooks/session_start.clj` |
| `scripts/succession-pre-tool-use.sh` | `bb/src/succession/hooks/pre_tool_use.clj` |
| `scripts/succession-stop.sh` | `bb/src/succession/hooks/stop.clj` |
| `scripts/succession-extract-cli.sh` | `bb/src/succession/extract.clj` |
| `scripts/succession-skill-extract.sh` | `bb/src/succession/skill.clj` |
| `tests/test_succession.sh` | `bb/test/succession/*_test.clj` (12 files, 66 tests) |

### SOUL framework (unused, hooks never installed)

| File/Dir | Reason |
|----------|--------|
| `soul-init.sh` | Installs SOUL hooks — superseded by Succession |
| `skills/soul/scripts/*.sh` (7 files) | SOUL hook scripts — never installed, `.soul/hooks/` is empty |
| `.soul/` directory | Empty hooks/, invariants/, log/ dirs — dead |
| `experiments/06-soul-bench/test_hooks.sh` | Tests `.soul/hooks/*.sh` which don't exist |

### Keep

- `scripts/succession-init.sh` — the bootstrapper, still bash, auto-detects bb. **But** update it to remove bash fallback (lines 36-41 copy bash scripts as fallback). Make bb required, not optional.
- `scripts/SKILL.md` — the `/succession` skill file, copied by init
- `experiments/01-05/` — standalone experiment harnesses, not framework code

---

## Modifications

### `scripts/succession-init.sh`

- Remove lines 35-41 (copying bash scripts to `~/.succession/scripts/`)
- Remove the bash fallback branch (lines 106-109) — bb is now required
- Add a hard fail if `bb` is not found: `echo "Error: babashka (bb) required"; exit 1`
- Remove `mkdir -p "${GLOBAL_DIR}/scripts"` — no longer needed
- Keep the bb hook registration as-is (lines 100-104)

### `.claude/settings.local.json`

- Remove dead SOUL hook permission: `Bash(/Users/danieltan/Projects/g-daniel/agent-lineage-evolution/.soul/hooks/session-start.sh)`

---

## Files summary

| Action | Path |
|--------|------|
| **Delete** | `scripts/lib.sh` |
| **Delete** | `scripts/succession-resolve.sh` |
| **Delete** | `scripts/succession-session-start.sh` |
| **Delete** | `scripts/succession-pre-tool-use.sh` |
| **Delete** | `scripts/succession-stop.sh` |
| **Delete** | `scripts/succession-extract-cli.sh` |
| **Delete** | `scripts/succession-skill-extract.sh` |
| **Delete** | `tests/test_succession.sh` |
| **Delete** | `soul-init.sh` |
| **Delete** | `skills/soul/scripts/` (entire dir) |
| **Delete** | `.soul/` (entire dir) |
| **Delete** | `experiments/06-soul-bench/test_hooks.sh` |
| **Modify** | `scripts/succession-init.sh` — remove bash fallback, require bb |
| **Modify** | `.claude/settings.local.json` — remove dead SOUL permission |

---

## Verification

```bash
# Confirm bb tests still pass (no deps on deleted bash files)
bb -cp bb/src:bb/test -e '(require (quote clojure.test)) ...' 

# Confirm init script works with bb
./scripts/succession-init.sh --project-only

# Confirm no dangling references to deleted files
grep -r "succession-resolve.sh\|succession-stop.sh\|succession-pre-tool-use.sh\|succession-session-start.sh\|lib.sh\|soul-init\|conscience.sh" --include='*.{sh,clj,json,md}' .
```
