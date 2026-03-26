# Evaluation Rubric — Compaction vs. Append-Only

## Scoring Dimensions

### 1. Contradiction Count
Count the number of statements in the memory file that contradict the current ground truth of the test repository.

**Examples of contradictions:**
- States file X uses library A, but file X was refactored to use library B
- States function Y takes 2 parameters, but it was changed to take 3
- States bug Z exists, but bug Z was fixed in session 6

**Scoring:** Raw count. Lower is better.

### 2. Staleness Count
Count references to entities that no longer exist or states that are no longer true.

**Examples of staleness:**
- References a file that was deleted
- Describes an API endpoint that was removed
- Mentions a dependency that was replaced

**Scoring:** Raw count. Lower is better.

### 3. File Size
Record the byte count of the memory file.

**Scoring:** Raw bytes. Context: a useful memory file should be compact enough to fit comfortably within a model's context window without triggering "Lost in the Middle" degradation.

### 4. Accuracy Score (0-10)

| Score | Criteria |
|-------|----------|
| 10 | All statements factually correct, no contradictions, no staleness |
| 8-9 | 1-2 minor inaccuracies, no contradictions |
| 6-7 | Some stale information but no outright contradictions |
| 4-5 | Multiple stale references and/or 1-2 contradictions |
| 2-3 | Significant contradictions that could mislead the agent |
| 0-1 | Mostly inaccurate, would actively harm agent performance |

## Ground Truth

The ground truth is established by the predetermined change sequence in the test repo. After all 10 sessions, the evaluator has a definitive list of what is true about the codebase, enabling objective scoring.

## Evaluator

Scoring should be performed by a human reviewer with access to the ground truth document and the memory file snapshot. For reproducibility, the ground truth is encoded in `setup.sh` as comments documenting the expected final state.
