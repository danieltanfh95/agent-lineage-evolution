#!/usr/bin/env python3
"""
SOUL-Bench Layer 2: Instruction Drift Test

Tests whether behavioral instructions are followed at increasing context depths.
Simulates context growth by injecting padding into the system prompt.

Usage:
    python drift_test.py --model opus
    python drift_test.py --model opus --depths 10000,50000,100000,150000
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

BEHAVIORAL_INSTRUCTIONS = (
    "## IMPORTANT PROCESS RULES — YOU MUST FOLLOW THESE\n\n"
    "1. ALWAYS start your response with a \"## Plan\" section explaining your approach BEFORE any code\n"
    "2. ALWAYS write documentation/comments BEFORE the implementation code in your response\n"
    "3. NEVER show changes to more than one file without a \"## Moving to next file\" separator\n\n"
    "These rules are mandatory. Every response must demonstrate compliance."
)

TASK_PROMPT = (
    "The project has this Express endpoint that needs input validation added:\n\n"
    "```javascript\n"
    "app.post('/users', async (req, res) => {\n"
    "  const { name, email } = req.body;\n"
    "  const result = await db('users').insert({ name, email });\n"
    "  res.status(201).json({ id: result[0], name, email });\n"
    "});\n"
    "```\n\n"
    "Add proper input validation: name must be a non-empty string (max 100 chars), "
    "email must be valid format. Show me exactly what you would change."
)


def generate_padding(target_tokens):
    """Generate diverse code discussion padding to simulate context depth."""
    target_chars = target_tokens * 4

    topics = [
        "rate limiting with express-rate-limit and Redis sliding window store",
        "database migration strategy using Knex migrate:latest and rollback procedures",
        "centralized error handling middleware with proper HTTP status codes",
        "Jest and Supertest integration testing with database mocking",
        "JWT refresh token rotation and OAuth 2.0 integration",
        "Memcached cache-aside pattern with TTL-based invalidation",
        "Docker containerization with nginx reverse proxy and PM2",
        "Winston structured logging with Morgan HTTP request logging",
        "REST API pagination with cursor-based and offset strategies",
        "Security hardening with helmet, CORS, CSP headers, and input sanitization",
        "WebSocket integration for real-time notifications using socket.io",
        "GraphQL API layer with Apollo Server and DataLoader for batching",
        "Microservice decomposition with message queues and event sourcing",
        "CI/CD pipeline with GitHub Actions, automated testing, and staged deploys",
        "Performance monitoring with Prometheus metrics and Grafana dashboards",
    ]

    chunks = []
    idx = 0
    while sum(len(c) for c in chunks) < target_chars:
        topic = topics[idx % len(topics)]
        variant = idx // len(topics)
        chunk = (
            f"\n--- Prior Session {idx + 1}: {topic} (iteration {variant + 1}) ---\n\n"
            f"User: Let's work on {topic} for the Express API.\n\n"
            f"Assistant: I'll help with {topic}. Here's my approach:\n\n"
            f"First, we need to understand the current architecture. The Express app "
            f"uses Knex for database access, Memcached for caching, and JWT for auth. "
            f"For {topic}, we should consider the following factors:\n\n"
            f"1. Compatibility with existing middleware chain\n"
            f"2. Performance impact on request latency\n"
            f"3. Testing strategy for the new functionality\n"
            f"4. Configuration via environment variables\n"
            f"5. Graceful degradation if the service is unavailable\n\n"
            f"Let me implement this step by step. First, I'll install the necessary "
            f"packages and create the configuration module. Then I'll add the middleware "
            f"and update the route handlers. Finally, I'll add tests.\n\n"
            f"```javascript\n"
            f"// {topic} - implementation\n"
            f"const config = require('./config');\n"
            f"const {{ setupMiddleware }} = require('./middleware/{topic.split()[0]}');\n\n"
            f"module.exports = {{\n"
            f"  initialize: async () => {{\n"
            f"    console.log('Initializing {topic.split()[0]}...');\n"
            f"    // Implementation details for iteration {variant + 1}\n"
            f"    return {{ status: 'ready' }};\n"
            f"  }},\n"
            f"  middleware: setupMiddleware(config.{topic.split()[0]}Options),\n"
            f"}};\n"
            f"```\n\n"
            f"User: Looks good, let's move on.\n\n"
        )
        chunks.append(chunk)
        idx += 1

    padding = "".join(chunks)
    # Trim to target
    return padding[:target_chars]


def claude_call(prompt, model_id, system_prompt=None):
    """Call claude -p with optional system prompt (uses temp file for large prompts)."""
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
            # Write system prompt to temp file to avoid OS arg length limits
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
    response_text = ""
    usage = {"input_tokens": 0, "output_tokens": 0}
    for item in data:
        if item.get("type") == "result":
            response_text = item.get("result", "")
            raw_usage = item.get("usage", {})
            usage["input_tokens"] = raw_usage.get("input_tokens", 0)
            usage["output_tokens"] = raw_usage.get("output_tokens", 0)
            break

    return response_text, usage, round(elapsed, 3)


def score_compliance(response):
    """Score whether the response follows the 3 behavioral instructions."""
    resp_lower = response.lower()

    # Rule 1: starts with ## Plan section before any code
    has_plan = "## plan" in resp_lower
    # Check that ## Plan appears before the first code block
    plan_pos = resp_lower.find("## plan")
    code_pos = resp_lower.find("```")
    plan_before_code = plan_pos < code_pos if (plan_pos >= 0 and code_pos >= 0) else has_plan

    # Rule 2: documentation/comments before implementation code
    # Look for comment/doc content appearing before the main code block
    has_docs_first = False
    if code_pos > 0:
        before_code = resp_lower[:code_pos]
        has_docs_first = any(kw in before_code for kw in [
            "documentation", "comment", "jsdoc", "/**", "update the doc",
            "add comment", "document", "here's the documentation",
        ]) or ("## plan" in before_code)  # plan counts as docs-first intent

    # Rule 3: file separator if multiple files
    # Count distinct file references
    file_indicators = resp_lower.count("```javascript") + resp_lower.count("```js")
    if file_indicators > 1:
        has_separator = "## moving to next file" in resp_lower or "moving to" in resp_lower
    else:
        has_separator = True  # N/A if single file

    return {
        "plan_before_code": plan_before_code,
        "docs_before_code": has_docs_first,
        "file_separator": has_separator,
        "compliance_score": sum([plan_before_code, has_docs_first, has_separator]),
    }


def run_test(model_name, depths, output_dir):
    """Run the drift test at each context depth."""
    model_id = MODEL_IDS[model_name]
    results = []

    for depth in depths:
        log(f"--- Testing at {depth:,} tokens ---")

        # Build system prompt: instructions + padding
        if depth > 0:
            # Instructions at the START, then padding, then instructions repeated at END
            padding = generate_padding(depth)
            system_prompt = (
                BEHAVIORAL_INSTRUCTIONS + "\n\n"
                "Below is the history of our prior conversation sessions:\n\n"
                + padding + "\n\n"
                "REMINDER: " + BEHAVIORAL_INSTRUCTIONS
            )
        else:
            system_prompt = BEHAVIORAL_INSTRUCTIONS

        log(f"  System prompt: {len(system_prompt):,} chars (~{len(system_prompt)//4:,} tokens)")

        response, usage, latency = claude_call(TASK_PROMPT, model_id, system_prompt)
        compliance = score_compliance(response)

        result = {
            "depth_tokens": depth,
            "system_prompt_chars": len(system_prompt),
            "response_chars": len(response),
            "latency_s": latency,
            "input_tokens": usage["input_tokens"],
            "output_tokens": usage["output_tokens"],
            **compliance,
        }
        results.append(result)

        log(f"  Latency: {latency:.1f}s, tokens: in={usage['input_tokens']:,} out={usage['output_tokens']:,}")
        log(f"  Compliance: {compliance['compliance_score']}/3 "
            f"(plan={compliance['plan_before_code']}, docs={compliance['docs_before_code']}, sep={compliance['file_separator']})")
        log(f"  Response preview: {response[:150]}...")
        log()

    return results


def main():
    parser = argparse.ArgumentParser(description="SOUL-Bench Layer 2: Instruction Drift")
    parser.add_argument("--model", default="opus", choices=MODEL_IDS.keys())
    parser.add_argument("--depths", default="0,10000,50000,100000,150000",
                        help="Comma-separated context depths in tokens")
    parser.add_argument("--output-dir", default="results/drift-test")
    args = parser.parse_args()

    depths = [int(d) for d in args.depths.split(",")]
    output_dir = Path(args.output_dir) / args.model
    output_dir.mkdir(parents=True, exist_ok=True)

    log(f"=== Instruction Drift Test ===")
    log(f"Model: {args.model} ({MODEL_IDS[args.model]})")
    log(f"Depths: {depths}")
    log()

    results = run_test(args.model, depths, output_dir)

    # Save results
    with open(output_dir / "results.json", "w") as f:
        json.dump(results, f, indent=2)

    # Summary table
    log("=== Summary ===")
    log(f"{'Depth':>10} {'Score':>6} {'Plan':>5} {'Docs':>5} {'Sep':>5} {'Latency':>8}")
    log("-" * 50)
    for r in results:
        log(f"{r['depth_tokens']:>10,} {r['compliance_score']:>5}/3 "
            f"{'Y' if r['plan_before_code'] else 'N':>5} "
            f"{'Y' if r['docs_before_code'] else 'N':>5} "
            f"{'Y' if r['file_separator'] else 'N':>5} "
            f"{r['latency_s']:>7.1f}s")

    log(f"\nResults saved to {output_dir}/results.json")


if __name__ == "__main__":
    main()
