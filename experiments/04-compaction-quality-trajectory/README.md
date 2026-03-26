# Experiment 04: Compaction Quality Trajectory

## Hypothesis

SOUL.md quality (measured by section completeness, accuracy, and information density) improves over the first 10-15 compaction cycles, as the rolling compaction mechanism refines and compresses accumulated knowledge.

## Design

**Type:** Longitudinal observation (single-subject time series)

**Measurement points:** After each of 15 compaction cycles during genuine work sessions

**Dependent variables (per cycle):**
- File size (bytes)
- Section completeness (0-5 scale)
- Accuracy (% of statements verifiably correct)
- Contradiction count
- Information density (useful facts per 100 words)

## Protocol

### Setup

1. Initialize SOUL in a repository where genuine development work will occur
2. Ensure `autoCommit` is enabled in `.soul/config.json` so each compaction creates a git commit
3. Note the initial SOUL.md state (template with minimal content)

### Execution

Over the course of normal development work:

1. Use Claude Code for genuine development tasks
2. Allow compaction to run naturally on PostCompact events
3. After each compaction, the updated SOUL.md is auto-committed to git
4. Continue until 15 compaction cycles have occurred

### Data Collection

After 15 cycles, extract snapshots from git history:

```bash
# List all compaction commits
git log --oneline --all -- .soul/SOUL.md

# Extract each version
for commit in $(git log --format='%H' -- .soul/SOUL.md | tac); do
  git show "${commit}:.soul/SOUL.md" > "snapshot-$(git log -1 --format='%ai' $commit | cut -d' ' -f1).md"
done
```

### Evaluation

Apply the evaluation rubric to each snapshot:

1. **File size:** `wc -c` on the snapshot
2. **Section completeness:** Score each of 5 expected sections (0 = missing, 1 = present and populated)
3. **Accuracy:** Sample 10 factual statements, verify against repo state at that commit
4. **Contradiction count:** Count statements that conflict with each other within the document
5. **Information density:** Count distinct, useful facts in a 100-word sample

### Analysis

Use `analysis.sh` to:
1. Tabulate metrics per cycle
2. Compute trend lines (linear regression)
3. Identify if quality plateaus, improves, or degrades

## Expected Outcome

- File size: initial rapid growth, then plateau as compaction balances new knowledge with pruning
- Section completeness: monotonically increasing from 0/5 to 5/5
- Accuracy: improving as compaction resolves contradictions
- Contradiction count: decreasing over time
- Information density: increasing as noise is pruned and knowledge is compressed

## Files

- `README.md` — This protocol document
- `evaluation-rubric.md` — Detailed scoring criteria per dimension
- `analysis.sh` — Script to tabulate and analyze results
