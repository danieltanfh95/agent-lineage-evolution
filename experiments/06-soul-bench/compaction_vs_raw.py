#!/usr/bin/env python3
"""
SOUL-Bench Layer 2 (Redesigned): Compacted SOUL vs Raw Transcript

Tests whether SOUL's compacted memory preserves instruction compliance
better than raw accumulated session transcripts.

Methodology:
  1. Build a large "conversation history" from 50 express-api-like sessions
  2. Arm A (SOUL): Compact the sessions into SOUL.md (~5k chars), then answer
  3. Arm B (Raw): Concatenate all sessions as raw text (~25k chars), then answer
  4. Arm C (No memory): No context at all
  5. Score instruction compliance for all three arms

This directly tests SOUL's value: does compaction preserve the instructions
and knowledge that matter, while raw accumulation buries them?

Usage:
    python compaction_vs_raw.py
    python compaction_vs_raw.py --sessions 50 --model opus
"""

import json
import subprocess
import tempfile
import time
import argparse
import sys
from pathlib import Path


def log(msg=""):
    print(msg, flush=True)


MODEL_IDS = {
    "haiku": "claude-haiku-4-5-20251001",
    "sonnet": "claude-sonnet-4-6",
    "opus": "claude-opus-4-6",
}

COMPACT_PROMPT = """You are a soul compaction system. Merge new session knowledge into the existing SOUL.md.

CURRENT SOUL.MD:
{current_soul}

SESSION CONTEXT:
{session_transcript}

INSTRUCTIONS:
1. Merge new knowledge into appropriate sections
2. Update Accumulated Knowledge with confirmed patterns and decisions
3. Update Predecessor Warnings if new failure modes were encountered
4. Update Current Understanding to reflect latest state
5. Resolve contradictions — newer information wins
6. PRUNE stale, redundant, or irrelevant information
7. Keep concise — compression, not accumulation
8. Preserve section structure (Identity, Accumulated Knowledge, Predecessor Warnings, Current Understanding, Skills)

Output ONLY the updated SOUL.md content. No fencing, no preamble."""

BEHAVIORAL_RULES = """## PROCESS RULES — MANDATORY
1. ALWAYS start with a "## Plan" section before any code
2. ALWAYS update documentation/comments BEFORE implementation code
3. NEVER modify more than one file without a "## Moving to next file" header
4. Use the spec format: Problem → Solution → Requirements → Verification"""

TASK_PROMPT = """Add input validation to this endpoint. Name must be non-empty (max 100 chars), email must be valid format.

```javascript
app.post('/users', async (req, res) => {
  const { name, email } = req.body;
  const result = await db('users').insert({ name, email });
  res.status(201).json({ id: result[0], name, email });
});
```

Show me exactly what you would change."""

INITIAL_SOUL = """## Identity
I am a coding assistant working on an Express.js REST API with JWT auth, Memcached caching, and Knex ORM on SQLite.

""" + BEHAVIORAL_RULES + """

## Accumulated Knowledge
- Database: SQLite via Knex query builder
- Auth: JWT with 24h expiry, bcrypt hashing
- Caching: Memcached (replaced Redis in session 6)
- Testing: Jest + Supertest, 80% coverage
- User deletion: soft-delete via POST /users/:id/deactivate (DELETE endpoint removed)

## Predecessor Warnings
- DO NOT use ignoreExpiration in JWT verify — caused expired tokens to be accepted
- DO NOT use raw SQL — all queries must go through Knex
- Always validate input before database operations

## Current Understanding
Express API with CRUD for users, JWT auth, Memcached caching, soft-delete pattern. Jest tests at 80% coverage.

## Skills
No specialized skills defined."""


def generate_sessions(n):
    """Generate n realistic session transcripts."""
    templates = [
        "## Session: Add rate limiting\n\nAdded express-rate-limit middleware with Redis store. 100 requests per 15 minutes per IP. Added X-RateLimit headers. Tests added for rate limit edge cases.\n\n## Git Log\n- feat: add rate limiting middleware",
        "## Session: Fix CORS configuration\n\nUpdated CORS to allow specific origins instead of wildcard. Added preflight handling for PUT/DELETE. Environment variable ALLOWED_ORIGINS for configuration.\n\n## Git Log\n- fix: restrict CORS to specific origins",
        "## Session: Add pagination\n\nImplemented cursor-based pagination for GET /users. Added ?cursor and ?limit query params. Default limit 20, max 100. Returns next_cursor in response.\n\n## Git Log\n- feat: cursor-based pagination for /users",
        "## Session: Database index optimization\n\nAdded indexes on users.email (unique), users.created_at, user_roles.user_id. Query time for user lookup dropped from 45ms to 3ms.\n\n## Git Log\n- perf: add database indexes",
        "## Session: Error handling middleware\n\nCentralized error handling with custom AppError class. All routes now throw typed errors. Middleware catches and formats JSON error responses with appropriate status codes.\n\n## Git Log\n- refactor: centralize error handling",
        "## Session: Add health check endpoint\n\nEnhanced /health to check database connectivity and Memcached availability. Returns {status, db, cache, uptime}. Added /health/ready for Kubernetes readiness probe.\n\n## Git Log\n- feat: enhance health check with dependency status",
        "## Session: Logging with Winston\n\nReplaced console.log with Winston structured logging. JSON format for production, pretty-print for development. Log levels configurable via LOG_LEVEL env var.\n\n## Git Log\n- refactor: add Winston structured logging",
        "## Session: API versioning\n\nAdded /api/v1/ prefix to all routes. Old routes redirect to v1 with deprecation header. Version negotiation via Accept header planned for v2.\n\n## Git Log\n- feat: add API versioning with /api/v1 prefix",
        "## Session: Webhook system\n\nAdded webhook registration and delivery for user events (create, update, deactivate). Webhooks stored in webhooks table. Delivery uses exponential backoff retry.\n\n## Git Log\n- feat: webhook system for user events",
        "## Session: Request validation with Joi\n\nAdded Joi schemas for all request bodies. Validation middleware returns structured error messages. Schemas defined alongside route handlers.\n\n## Git Log\n- feat: add Joi request validation",
    ]

    sessions = []
    for i in range(n):
        template = templates[i % len(templates)]
        # Add variation to avoid exact repetition
        variant = f"\n\n(Iteration {i // len(templates) + 1}, session {i + 1})"
        sessions.append(template + variant)

    return sessions


def claude_call(prompt, model_id, system_prompt=None):
    """Call claude -p with optional system prompt."""
    cmd = [
        "claude", "-p",
        "--model", model_id,
        "--tools", "",
        "--output-format", "json",
        "--no-session-persistence",
    ]

    tmp_file = None
    try:
        if system_prompt:
            tmp_file = tempfile.NamedTemporaryFile(mode="w", suffix=".txt", delete=False)
            tmp_file.write(system_prompt)
            tmp_file.close()
            cmd.extend(["--system-prompt-file", tmp_file.name])

        start = time.time()
        result = subprocess.run(
            cmd, input=prompt, capture_output=True, text=True, timeout=300,
        )
        elapsed = time.time() - start
    finally:
        if tmp_file:
            Path(tmp_file.name).unlink(missing_ok=True)

    if result.returncode != 0:
        raise RuntimeError(f"claude -p failed: {result.stderr[:300]}")

    data = json.loads(result.stdout)
    for item in data:
        if item.get("type") == "result":
            return item.get("result", ""), round(elapsed, 3)
    return "", 0


def compact_sessions(sessions, model_id):
    """Compact sessions one at a time into SOUL.md."""
    soul_md = INITIAL_SOUL
    for i, session in enumerate(sessions):
        prompt = COMPACT_PROMPT.format(current_soul=soul_md, session_transcript=session)
        new_soul, latency = claude_call(prompt, model_id)
        if "## Identity" in new_soul and len(new_soul) > 50:
            soul_md = new_soul
        log(f"    compact {i+1}/{len(sessions)}: {latency:.1f}s, mem={len(soul_md)} chars")
    return soul_md


def score_compliance(response):
    """Score instruction compliance."""
    resp_lower = response.lower()

    plan_pos = resp_lower.find("## plan")
    code_pos = resp_lower.find("```")
    plan_before_code = plan_pos >= 0 and (code_pos < 0 or plan_pos < code_pos)

    has_docs_first = False
    if code_pos > 0:
        before_code = resp_lower[:code_pos]
        has_docs_first = any(kw in before_code for kw in [
            "documentation", "comment", "jsdoc", "plan", "approach",
            "problem", "solution", "requirement", "verification",
        ])

    file_blocks = resp_lower.count("```javascript") + resp_lower.count("```js")
    if file_blocks > 1:
        has_separator = "## moving to next file" in resp_lower or "moving to" in resp_lower
    else:
        has_separator = True

    has_spec_format = any(kw in resp_lower for kw in ["problem", "solution", "requirement", "verification"])

    return {
        "plan_before_code": plan_before_code,
        "docs_before_code": has_docs_first,
        "file_separator": has_separator,
        "spec_format": has_spec_format,
        "score": sum([plan_before_code, has_docs_first, has_separator, has_spec_format]),
        "max": 4,
    }


def main():
    parser = argparse.ArgumentParser(description="SOUL-Bench Layer 2: Compaction vs Raw")
    parser.add_argument("--sessions", type=int, default=30, help="Number of sessions to accumulate")
    parser.add_argument("--compact-model", default="sonnet", choices=MODEL_IDS.keys())
    parser.add_argument("--reader-model", default="opus", choices=MODEL_IDS.keys())
    parser.add_argument("--output-dir", default="results/compaction-vs-raw")
    args = parser.parse_args()

    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    compact_model = MODEL_IDS[args.compact_model]
    reader_model = MODEL_IDS[args.reader_model]

    log(f"=== Layer 2: Compaction vs Raw ===")
    log(f"Sessions: {args.sessions}, Compact: {args.compact_model}, Reader: {args.reader_model}")

    # Generate sessions
    sessions = generate_sessions(args.sessions)
    raw_transcript = "\n\n---\n\n".join(sessions)
    log(f"Raw transcript: {len(raw_transcript):,} chars (~{len(raw_transcript)//4:,} tokens)")

    results = {}

    # --- Arm A: SOUL compacted ---
    log(f"\n--- Arm A: SOUL (compact with {args.compact_model}) ---")
    soul_md = compact_sessions(sessions, compact_model)
    log(f"Final SOUL.md: {len(soul_md)} chars")

    response_a, latency_a = claude_call(TASK_PROMPT, reader_model, system_prompt=soul_md)
    compliance_a = score_compliance(response_a)
    log(f"Compliance: {compliance_a['score']}/{compliance_a['max']} — {compliance_a}")
    results["soul"] = {"memory_chars": len(soul_md), "latency": latency_a, **compliance_a, "response": response_a}

    # --- Arm B: Raw transcript ---
    log(f"\n--- Arm B: Raw transcript ({len(raw_transcript):,} chars) ---")
    raw_context = INITIAL_SOUL + "\n\n--- ACCUMULATED SESSION HISTORY ---\n\n" + raw_transcript
    response_b, latency_b = claude_call(TASK_PROMPT, reader_model, system_prompt=raw_context)
    compliance_b = score_compliance(response_b)
    log(f"Compliance: {compliance_b['score']}/{compliance_b['max']} — {compliance_b}")
    results["raw"] = {"memory_chars": len(raw_context), "latency": latency_b, **compliance_b, "response": response_b}

    # --- Arm C: No memory ---
    log(f"\n--- Arm C: No memory ---")
    response_c, latency_c = claude_call(TASK_PROMPT, reader_model)
    compliance_c = score_compliance(response_c)
    log(f"Compliance: {compliance_c['score']}/{compliance_c['max']} — {compliance_c}")
    results["no-memory"] = {"memory_chars": 0, "latency": latency_c, **compliance_c, "response": response_c}

    # --- Summary ---
    log(f"\n=== Summary ===")
    log(f"{'Arm':<12} {'Context':>10} {'Score':>6} {'Plan':>5} {'Docs':>5} {'Sep':>5} {'Spec':>5} {'Latency':>8}")
    log("-" * 62)
    for arm, r in results.items():
        log(f"{arm:<12} {r['memory_chars']:>9}c {r['score']:>5}/{r['max']} "
            f"{'Y' if r['plan_before_code'] else 'N':>5} "
            f"{'Y' if r['docs_before_code'] else 'N':>5} "
            f"{'Y' if r['file_separator'] else 'N':>5} "
            f"{'Y' if r['spec_format'] else 'N':>5} "
            f"{r['latency']:>7.1f}s")

    # Save
    save_results = {k: {kk: vv for kk, vv in v.items() if kk != "response"} for k, v in results.items()}
    save_results["_responses"] = {k: v["response"][:500] for k, v in results.items()}
    with open(output_dir / "results.json", "w") as f:
        json.dump(save_results, f, indent=2)
    log(f"\nSaved to {output_dir}/results.json")


if __name__ == "__main__":
    main()
