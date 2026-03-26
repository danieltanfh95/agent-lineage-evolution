# Experiment 01: Compaction vs. Append-Only

## Hypothesis

Rolling compaction (SOUL's approach) produces a memory file with fewer contradictions, less stale information, and higher accuracy than append-only memory accumulation over the same sequence of sessions.

## Design

**Type:** Controlled comparison (within-subjects, counterbalanced)

**Independent variable:** Memory management strategy
- **Treatment:** SOUL rolling compaction enabled (compact.sh fires on PostCompact)
- **Control:** Append-only MEMORY.md (new observations appended, never pruned)

**Dependent variables:**
- Contradiction count (facts that conflict with current ground truth)
- Staleness count (references to deleted files, resolved bugs, or changed APIs)
- File size (bytes)
- Accuracy score (0-10, see evaluation rubric)

## Protocol

### Setup

1. Run `setup.sh` to create a test repository with a known initial codebase
2. The codebase contains 5 source files with documented APIs and dependencies
3. A predetermined sequence of 10 changes is applied across sessions:
   - 3 API changes (making earlier knowledge stale)
   - 2 bug fixes (making earlier bug reports resolved)
   - 2 dependency changes (making earlier dependency info wrong)
   - 2 new features (adding new knowledge)
   - 1 architectural refactor (invalidating structural assumptions)

### Execution

For each trial (3 trials with different change orderings):

1. Initialize a fresh test repo from `setup.sh`
2. **Treatment arm:** Initialize SOUL (`soul-init.sh`), run 10 sessions applying the predetermined changes, allowing compaction after each
3. **Control arm:** Same repo, same changes, but use append-only MEMORY.md instead of SOUL.md compaction
4. After all 10 sessions, snapshot the resulting memory file (SOUL.md or MEMORY.md)

### Evaluation

Apply the evaluation rubric to each snapshot:
1. Count contradictions against known ground truth
2. Count stale references
3. Record file size
4. Score accuracy (0-10) using the rubric

## Expected Outcome

The treatment (compaction) should show:
- Fewer contradictions (compaction resolves them)
- Fewer stale references (compaction prunes them)
- Smaller or similar file size (compaction compresses)
- Higher accuracy score

## Files

- `setup.sh` — Creates the test repository with known initial state
- `run-trial.sh` — Executes one trial (treatment or control)
- `evaluation-rubric.md` — Scoring criteria for memory file quality
