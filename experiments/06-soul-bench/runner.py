#!/usr/bin/env python3
"""
SOUL-Bench Runner

Runs a scenario through SOUL's compaction pipeline and generates
predictions for scoring.

Usage:
    python runner.py --scenario scenarios/express-api --condition soul-sonnet
    python runner.py --scenario scenarios/express-api --condition no-memory
"""

import json
import subprocess
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

SYSTEM_PROMPT = "You are a helpful assistant. Follow the user's instructions precisely. Be concise."


def claude_call(prompt, model_id):
    """Call claude -p with minimal system prompt, no tools."""
    start = time.time()
    result = subprocess.run(
        [
            "claude", "-p",
            "--model", model_id,
            "--tools", "",
            "--output-format", "json",
            "--no-session-persistence",
            "--system-prompt", SYSTEM_PROMPT,
        ],
        input=prompt,
        capture_output=True,
        text=True,
        timeout=300,
    )
    elapsed = time.time() - start

    if result.returncode != 0:
        raise RuntimeError(f"claude -p failed (exit {result.returncode}): {result.stderr[:500]}")

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


# --- Compaction prompt matching compact.sh's format ---
COMPACT_PROMPT = """You are a soul compaction system for the SOUL framework. Your job is to merge new session knowledge into an existing SOUL.md file.

CURRENT SOUL.MD:
{current_soul}

SESSION CONTEXT (recent transcript excerpts):
{session_transcript}

INSTRUCTIONS:
1. Merge any new knowledge from the session into the appropriate sections of SOUL.md
2. Update 'Accumulated Knowledge' with confirmed patterns, decisions, and discoveries
3. Update 'Predecessor Warnings' if new failure modes were encountered
4. Update 'Current Understanding' to reflect the latest state of the codebase/task
5. Resolve contradictions — newer information wins, but note the contradiction briefly
6. PRUNE information that is stale, redundant, or no longer relevant
7. Keep the document concise — compaction means compression, not accumulation
8. Preserve the existing section structure (Identity, Accumulated Knowledge, Predecessor Warnings, Current Understanding, Skills)
9. Do NOT add sections that don't exist in the original
10. Do NOT remove the ## Skills section or modify skill definitions unless the session explicitly changed them

Output ONLY the updated SOUL.md content. No markdown fencing, no preamble, no explanation. Just the raw markdown content of the updated file."""

QA_PROMPT = """You are a coding assistant with the following knowledge about the project you're working on:

{soul_md}

Based ONLY on the knowledge above, answer the following question about the project.
If the information is not in your knowledge, say "I don't know."
Be specific and concise.

Question: {question}"""

DEFAULT_INITIAL_SOUL = """## Identity
I am a coding assistant working on the express-api project — a Node.js/Express REST API.

## Accumulated Knowledge
No knowledge accumulated yet.

## Predecessor Warnings
No warnings yet.

## Current Understanding
Project has just been initialized. No sessions completed yet.

## Skills
No specialized skills defined."""


def load_initial_soul(scenario_dir):
    """Load initial soul from scenario directory, or fall back to default."""
    initial_soul_file = scenario_dir / "initial-soul.md"
    if initial_soul_file.exists():
        return initial_soul_file.read_text().strip()
    return DEFAULT_INITIAL_SOUL


def run_soul_condition(scenario_dir, model_name, output_dir):
    """Run compaction over all sessions, then answer questions."""
    model_id = MODEL_IDS[model_name]
    sessions_dir = scenario_dir / "sessions"
    session_files = sorted(sessions_dir.glob("session-*.md"))

    soul_md = load_initial_soul(scenario_dir)
    all_metrics = []

    for i, session_file in enumerate(session_files):
        transcript = session_file.read_text()
        prompt = COMPACT_PROMPT.format(
            current_soul=soul_md,
            session_transcript=transcript,
        )
        new_soul, usage, latency = claude_call(prompt, model_id)

        # Validate — must have ## Identity
        if "## Identity" not in new_soul or len(new_soul) < 50:
            log(f"    session {i+1}: REJECTED (missing structure), keeping previous")
        else:
            soul_md = new_soul

        metrics = {
            "session": i + 1,
            "latency_s": latency,
            "memory_size_chars": len(soul_md),
            "input_tokens": usage["input_tokens"],
            "output_tokens": usage["output_tokens"],
        }
        all_metrics.append(metrics)
        log(f"    session {i+1}/{len(session_files)}: {latency:.1f}s, mem={len(soul_md)} chars")

    return soul_md, all_metrics


def run_append_condition(scenario_dir, output_dir):
    """Append-only: concatenate all session summaries without compaction."""
    sessions_dir = scenario_dir / "sessions"
    session_files = sorted(sessions_dir.glob("session-*.md"))

    memory = "# Project Memory\n\n"
    for session_file in session_files:
        memory += session_file.read_text() + "\n\n---\n\n"

    return memory, []


def answer_questions(soul_md, questions, model_id, condition):
    """Answer all questions using the memory as context."""
    predictions = []
    for q in questions:
        if condition == "no-memory":
            prompt = (
                f"You are a coding assistant. Answer the following question about a project.\n"
                f"If you don't have enough information, say \"I don't know.\"\n"
                f"Be specific and concise.\n\n"
                f"Question: {q['question']}"
            )
        else:
            prompt = QA_PROMPT.format(soul_md=soul_md, question=q["question"])

        answer, usage, latency = claude_call(prompt, model_id)
        predictions.append({
            "id": q["id"],
            "category": q["category"],
            "question": q["question"],
            "prediction": answer,
            "latency_s": latency,
        })
        log(f"    Q {q['id']}: {latency:.1f}s")

    return predictions


def main():
    parser = argparse.ArgumentParser(description="SOUL-Bench Runner")
    parser.add_argument("--scenario", required=True, help="Path to scenario directory")
    parser.add_argument("--condition", required=True,
                        choices=["soul-sonnet", "soul-haiku", "soul-opus", "append-only", "no-memory"])
    parser.add_argument("--output-dir", default="results")
    args = parser.parse_args()

    scenario_dir = Path(args.scenario)
    output_dir = Path(args.output_dir) / scenario_dir.name / args.condition
    output_dir.mkdir(parents=True, exist_ok=True)

    # Load ground truth
    gt = json.load(open(scenario_dir / "ground-truth.json"))
    questions = gt["questions"]

    log(f"=== SOUL-Bench: {scenario_dir.name} / {args.condition} ===")
    log(f"Sessions: {len(list((scenario_dir / 'sessions').glob('session-*.md')))}")
    log(f"Questions: {len(questions)}")
    log()

    # --- Run compaction ---
    if args.condition.startswith("soul-"):
        model_name = args.condition.replace("soul-", "")
        log(f"Running compaction with {model_name}...")
        soul_md, comp_metrics = run_soul_condition(scenario_dir, model_name, output_dir)
    elif args.condition == "append-only":
        log("Running append-only (no compaction)...")
        soul_md, comp_metrics = run_append_condition(scenario_dir, output_dir)
    else:
        log("Running no-memory baseline...")
        soul_md = ""
        comp_metrics = []

    # Save final memory
    (output_dir / "memory.md").write_text(soul_md)
    log(f"\nFinal memory: {len(soul_md)} chars")

    # Save compaction metrics
    if comp_metrics:
        with open(output_dir / "compaction-metrics.jsonl", "w") as f:
            for m in comp_metrics:
                f.write(json.dumps(m) + "\n")

    # --- Answer questions ---
    log(f"\nAnswering {len(questions)} questions...")
    reader_model = MODEL_IDS["opus"]
    predictions = answer_questions(soul_md, questions, reader_model, args.condition)

    # Save predictions
    with open(output_dir / "predictions.json", "w") as f:
        json.dump(predictions, f, indent=2)

    log(f"\nResults written to {output_dir}/")

    # Quick score preview
    from scorer import score_predictions
    scores = score_predictions(predictions, questions)
    log(f"\n=== Quick Score ===")
    for cat, s in sorted(scores.items()):
        log(f"  {cat}: {s['correct']}/{s['total']} ({s['pct']:.0f}%)")


if __name__ == "__main__":
    main()
