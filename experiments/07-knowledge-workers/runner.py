#!/usr/bin/env python3
"""
Knowledge Worker Experiment Runner

Tests whether SOUL-configured knowledge workers get better outputs
than CLAUDE.md or no-memory baselines.

Usage:
    python runner.py --scenario scenarios/pm-specs --condition soul-skill
    python runner.py --scenario scenarios/analyst-data --condition no-memory
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


MODEL_ID = "claude-opus-4-6"
SYSTEM_PROMPT_MINIMAL = "You are a helpful assistant. Be concise and follow instructions."


def claude_call(prompt, system_prompt=None):
    """Call claude -p with optional system prompt via temp file."""
    cmd = [
        "claude", "-p",
        "--model", MODEL_ID,
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
        else:
            cmd.extend(["--system-prompt", SYSTEM_PROMPT_MINIMAL])

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


def run_scenario(scenario_dir, condition):
    """Run a knowledge worker scenario through the specified condition."""
    scenario_dir = Path(scenario_dir)
    setup = json.load(open(scenario_dir / "setup-answers.json"))
    gt = json.load(open(scenario_dir / "ground-truth.json"))

    # Build the memory context based on condition
    if condition == "soul-skill":
        # Simulate what /soul setup produces
        system_prompt = (
            "You have persistent memory about this user and project:\n\n"
            + setup["soul_md"] + "\n\n"
            "--- INVARIANTS ---\n"
            + setup["invariants_behavior"] + "\n\n"
            "Follow ALL rules in the Identity section without exception. "
            "Reference Accumulated Knowledge when relevant. "
            "Communicate in language appropriate to the user's role."
        )
    elif condition == "claude-md":
        # Same info but as a flat CLAUDE.md-style system prompt
        system_prompt = (
            f"# Project Configuration\n\n"
            f"## User Role\n{setup['role']}\n\n"
            f"## Rules\n{setup['rules']}\n\n"
            f"## Project Knowledge\n{setup['knowledge']}\n"
        )
    else:
        system_prompt = None  # no-memory

    # Track preference update for session 4→5
    preference_updated = False
    results = {}

    session_files = sorted(scenario_dir.glob("sessions/*.md"))
    for session_file in session_files:
        session_name = session_file.stem  # e.g., "task-02", "update-04"
        session_num = session_name.split("-")[1]  # e.g., "02", "04"
        task = session_file.read_text().strip()

        log(f"  Session {session_num} ({session_name}):")

        if "update-" in session_name:
            # Preference update session — modify the system prompt
            if condition == "soul-skill":
                # SOUL would update SOUL.md — simulate by appending the preference
                system_prompt += "\n\nUPDATED PREFERENCE: User now prefers tables over bullet points for comparisons and requirements."
                preference_updated = True
                log(f"    Preference updated (tables over bullets)")
            elif condition == "claude-md":
                system_prompt += "\n\n## Updated Preference\nUse tables instead of bullet points for comparisons and requirements."
                preference_updated = True
                log(f"    Preference updated (tables over bullets)")
            else:
                log(f"    No memory — preference update ignored")
            continue

        # Issue the task
        response, latency = claude_call(task, system_prompt)
        log(f"    Response: {len(response)} chars, {latency:.1f}s")
        log(f"    Preview: {response[:100]}...")

        results[session_num] = {
            "session": session_name,
            "response": response,
            "latency": latency,
        }

    return results, gt


def score_session(response, checks):
    """Score a single session response against ground truth checks."""
    resp_lower = response.lower()
    scores = {}

    for check in checks:
        check_id = check["id"]

        if "keyword_present" in check:
            # At least one keyword must be present
            found = any(kw.lower() in resp_lower for kw in check["keyword_present"])
            scores[check_id] = 1 if found else 0
        elif "keyword_absent" in check:
            # None of the keywords should be present
            absent = all(kw.lower() not in resp_lower for kw in check["keyword_absent"])
            scores[check_id] = 1 if absent else 0

    return scores


def main():
    parser = argparse.ArgumentParser(description="Knowledge Worker Experiment")
    parser.add_argument("--scenario", required=True)
    parser.add_argument("--condition", required=True,
                        choices=["soul-skill", "claude-md", "no-memory"])
    parser.add_argument("--output-dir", default="results")
    args = parser.parse_args()

    scenario_dir = Path(args.scenario)
    output_dir = Path(args.output_dir) / scenario_dir.name / args.condition
    output_dir.mkdir(parents=True, exist_ok=True)

    log(f"=== Knowledge Worker: {scenario_dir.name} / {args.condition} ===")

    results, gt = run_scenario(scenario_dir, args.condition)

    # Score each session
    all_scores = {}
    total_correct = 0
    total_checks = 0

    for session_num, session_gt in gt.get("sessions", {}).items():
        if session_num not in results:
            continue
        response = results[session_num]["response"]
        checks = session_gt["checks"]
        scores = score_session(response, checks)
        all_scores[session_num] = scores

        correct = sum(scores.values())
        total = len(scores)
        total_correct += correct
        total_checks += total
        log(f"  Session {session_num}: {correct}/{total} — {scores}")

    # Summary
    pct = (total_correct / total_checks * 100) if total_checks else 0
    log(f"\n=== Score: {total_correct}/{total_checks} ({pct:.0f}%) ===")

    # Save results
    output = {
        "scenario": scenario_dir.name,
        "condition": args.condition,
        "sessions": {k: {"response": v["response"], "latency": v["latency"]} for k, v in results.items()},
        "scores": all_scores,
        "total_correct": total_correct,
        "total_checks": total_checks,
        "percentage": round(pct, 1),
    }
    with open(output_dir / "results.json", "w") as f:
        json.dump(output, f, indent=2)

    log(f"Results saved to {output_dir}/results.json")


if __name__ == "__main__":
    main()
