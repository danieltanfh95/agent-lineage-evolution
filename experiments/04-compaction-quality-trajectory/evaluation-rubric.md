# Evaluation Rubric — Compaction Quality Trajectory

## 1. File Size (bytes)

Measured with `wc -c`. No scoring — raw metric tracked over time.

**Context:** A useful SOUL.md should be large enough to contain meaningful knowledge but small enough to avoid "Lost in the Middle" degradation when injected into context. Target range: 500-5000 bytes.

## 2. Section Completeness (0-5)

Score 1 point for each section that exists AND contains meaningful content (not just template placeholders):

| Section | Criteria for "populated" |
|---------|------------------------|
| Identity | Contains at least one sentence describing the agent's role |
| Accumulated Knowledge | Contains at least 3 confirmed facts |
| Predecessor Warnings | Contains at least 1 warning OR explicitly states "none yet" |
| Current Understanding | Contains at least 2 sentences about current codebase/task state |
| Skills | Contains at least 1 skill declaration |

**Scoring:** 0-5 integer.

## 3. Accuracy (%)

Sample 10 factual statements from the SOUL.md. For each:
- Verify against the actual repository state at the time of that compaction commit
- Score 1 if correct, 0 if incorrect or unverifiable

**Scoring:** (correct / 10) * 100 = percentage.

**Examples:**
- "The project uses Express for routing" → check package.json → correct/incorrect
- "The database is PostgreSQL" → check db.ts → correct/incorrect
- "Tests use vitest" → check test files → correct/incorrect

## 4. Contradiction Count

Count pairs of statements within the same SOUL.md that contradict each other.

**Examples of contradictions:**
- "Uses Express" and "Uses Hono" in the same document
- "Database is PostgreSQL" and "Database is SQLite" in the same document
- "Never use default exports" and a skill declaration suggesting default exports

**Scoring:** Raw count. Lower is better.

## 5. Information Density (facts per 100 words)

1. Select a representative 100-word passage from the Accumulated Knowledge section
2. Count the number of distinct, useful facts in that passage
3. A "fact" is a verifiable statement about the project, codebase, or agent behavior

**Examples of facts:**
- "The API uses JWT authentication" (1 fact)
- "Express server on port 3000" (1 fact)
- "Users table has columns: id, name, email, created_at" (1 fact with 4 specifics = 1 fact)

**Non-facts (don't count):**
- Filler phrases ("It is important to note that...")
- Vague statements ("The project is well-structured")
- Duplicated information

**Scoring:** Facts per 100 words. Higher is better.

## Recording Template

```json
{
  "cycle": <number>,
  "commit": "<git-hash>",
  "date": "<YYYY-MM-DD>",
  "file_size_bytes": <bytes>,
  "section_completeness": <0-5>,
  "accuracy_pct": <0-100>,
  "contradiction_count": <count>,
  "info_density": <facts-per-100-words>
}
```
