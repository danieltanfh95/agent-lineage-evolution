# Experiment 03: Genome Cascade Setup Time

## Hypothesis

Agents initialized with a rich genome cascade (base + language + archetype) require fewer turns to complete a standardized task in a new repository compared to agents with only a minimal genome (base only), because the inherited knowledge reduces the need for discovery and re-learning.

## Design

**Type:** Between-conditions comparison (counterbalanced)

**Independent variable:** Genome richness
- **Rich:** base.md + typescript.md + backend-api.md genomes
- **Minimal:** base.md only (default soul-init.sh output)

**Dependent variables:**
- Turns to task completion
- Invariant compliance score (0-5)
- Task quality score (0-10, see tasks.md)

**Task:** Set up a new TypeScript API project from scratch with:
- Express server with 3 CRUD endpoints
- Input validation on all endpoints
- Database connection (SQLite)
- Health check endpoint
- Proper error handling

## Protocol

### Setup

1. Prepare genome files:
   - `genomes/minimal/base.md` — Default base genome from soul-init.sh
   - `genomes/rich/base.md` — Same base genome
   - `genomes/rich/typescript.md` — TypeScript-specific patterns and preferences
   - `genomes/rich/backend-api.md` — Backend API patterns (REST conventions, validation, error handling)

2. Create invariants (same for both conditions):
   - All endpoints must validate input
   - Use TypeScript strict mode
   - No circular dependencies
   - Error responses must include status code and message

### Execution

For each trial (3 trials per condition, 6 total):

1. Create a fresh empty directory
2. Initialize git
3. Run soul-init.sh
4. Copy the appropriate genome set to `~/.soul/genome/`
5. Update `.soul/config.json` genome order
6. Start a Claude Code session with the task prompt from `tasks.md`
7. Count turns until the agent declares the task complete
8. Evaluate the result against the task quality rubric

### Evaluation

For each trial, record:
- **Turns to completion:** Number of agent turns (messages) from start to declared completion
- **Invariant compliance:** 0-5 score based on how many invariants were satisfied
- **Quality score:** 0-10 based on rubric in tasks.md

## Expected Outcome

- Rich genome: fewer turns (agent starts with TypeScript and API patterns)
- Minimal genome: more turns (agent must discover patterns from scratch)
- Quality scores should be similar (both agents are capable, just different starting points)
- Invariant compliance should be higher with rich genome (patterns are pre-loaded)

## Files

- `README.md` — This protocol document
- `genomes/rich/` — Rich genome set (base + typescript + backend-api)
- `genomes/minimal/` — Minimal genome (base only)
- `tasks.md` — Standardized task definition and quality rubric
