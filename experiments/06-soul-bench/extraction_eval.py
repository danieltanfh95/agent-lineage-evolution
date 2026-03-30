#!/usr/bin/env python3
"""
SOUL Extraction Eval — Benchmarks correction detection and pattern extraction.

Tests whether SOUL's 3-tier correction detection pipeline (keyword scan -> Haiku
confirmation -> Sonnet extraction) outperforms simpler approaches at detecting
user corrections, confirmations, and preferences in coding session transcripts.

Conditions:
  soul          — Full 3-tier pipeline via conscience.sh, turn-by-turn
  append-only   — No real-time detection; ask Sonnet to find patterns at the end
  keyword-only  — Tier 1 only (keyword scan), every hit triggers extraction
  single-shot   — Full transcript to Sonnet in one shot, extract all patterns

Usage:
    python extraction_eval.py --scenario L1-obvious --condition soul
    python extraction_eval.py --scenario L1-obvious --condition all
    python extraction_eval.py --all
"""

import json
import subprocess
import tempfile
import shutil
import time
import argparse
import os
from pathlib import Path


def log(msg=""):
    print(msg, flush=True)


SCRIPT_DIR = Path(__file__).parent
REPO_ROOT = SCRIPT_DIR.parent.parent
HOOKS_DIR = REPO_ROOT / ".soul" / "hooks"
SCENARIOS_DIR = SCRIPT_DIR / "scenarios" / "extraction"
RESULTS_DIR = SCRIPT_DIR / "results" / "extraction"

MODEL_IDS = {
    "haiku": "claude-haiku-4-5-20251001",
    "sonnet": "claude-sonnet-4-6",
    "opus": "claude-opus-4-6",
}

INITIAL_SOUL = """# Soul

## Identity
I am a coding assistant working on a software project.

## Accumulated Knowledge
(none yet)

## Predecessor Warnings
(none yet)

## Current Understanding
Starting a new project.

## Skills
No specialized skills defined.
"""

EXTRACTION_PROMPT = """You are a pattern extraction system. Read this conversation transcript and identify:

1. USER CORRECTIONS — the user told the agent to stop doing something or do something differently
2. CONFIRMED APPROACHES — the user validated a non-obvious choice the agent made
3. BEHAVIORAL PREFERENCES — how the user wants to work (tone, process, style)

For each pattern, classify scope:
- "repo" — specific to this project
- "cross-project" — applies everywhere

TRANSCRIPT:
{transcript}

Output ONLY valid JSON (no markdown fencing):
{{
  "patterns": [
    {{
      "type": "correction|confirmation|preference",
      "scope": "repo|cross-project",
      "summary": "one-line description",
      "detail": "what to do differently and why",
      "source": "brief quote from transcript"
    }}
  ]
}}

Rules:
- Only extract patterns the user explicitly expressed or clearly demonstrated
- Do not infer preferences from silence
- Routine acknowledgments (ok, thanks, got it) are NOT confirmations
- A confirmation requires the user to validate a NON-OBVIOUS or NON-DEFAULT choice
- Navigational "no" (choosing between options) is NOT a correction
- Domain language (error reports, stop flags) used in feature context is NOT a correction
- If no patterns found, return empty arrays"""


def claude_call(prompt, model_id, system_prompt=None, timeout=120):
    """Call claude -p with optional system prompt. Returns (result_text, elapsed)."""
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
            tmp_file = tempfile.NamedTemporaryFile(
                mode="w", suffix=".txt", delete=False
            )
            tmp_file.write(system_prompt)
            tmp_file.close()
            cmd.extend(["--system-prompt-file", tmp_file.name])

        start = time.time()
        result = subprocess.run(
            cmd, input=prompt, capture_output=True, text=True, timeout=timeout,
        )
        elapsed = time.time() - start
    except subprocess.TimeoutExpired:
        return "", 0
    finally:
        if tmp_file:
            Path(tmp_file.name).unlink(missing_ok=True)

    if result.returncode != 0:
        log(f"  WARNING: claude -p failed: {result.stderr[:200]}")
        return "", 0

    try:
        data = json.loads(result.stdout)
        for item in data:
            if item.get("type") == "result":
                return item.get("result", ""), round(elapsed, 3)
    except json.JSONDecodeError:
        log(f"  WARNING: failed to parse claude output")

    return "", 0


def setup_soul_dir(work_dir: Path):
    """Set up a minimal .soul/ directory for hook testing."""
    soul_dir = work_dir / ".soul"
    soul_dir.mkdir(parents=True, exist_ok=True)

    # Copy hooks
    hooks_dst = soul_dir / "hooks"
    hooks_dst.mkdir(exist_ok=True)
    for hook_file in HOOKS_DIR.glob("*.sh"):
        shutil.copy2(hook_file, hooks_dst / hook_file.name)
        (hooks_dst / hook_file.name).chmod(0o755)

    # Write SOUL.md
    (soul_dir / "SOUL.md").write_text(INITIAL_SOUL)

    # Write minimal invariants
    inv_dir = soul_dir / "invariants"
    inv_dir.mkdir(exist_ok=True)
    (inv_dir / "behavior.md").write_text(
        "- Never auto-commit without explicit user request\n"
    )

    # Write config
    config = {
        "conscience": {
            "model": "haiku",
            "auditEveryNTurns": 999,  # disable audit — we only want correction detection
            "alwaysAuditKeywords": [],
            "killAfterNViolations": 999,
            "contextTurns": 10,
            "correctionDetection": True,
            "correctionKeywords": [
                "no", "don't", "stop", "instead", "wrong", "not what I",
                "I prefer", "always use", "never use", "I already told",
            ],
        },
        "patterns": {
            "model": "sonnet",
            "extractEveryKTokens": 1,  # extract on every correction flag
            "promoteToCrossProject": False,
        },
    }
    (soul_dir / "config.json").write_text(json.dumps(config, indent=2))

    # Create log dir
    (soul_dir / "log").mkdir(exist_ok=True)

    return soul_dir


def write_transcript(turns: list, output_path: Path, up_to_turn: int = None):
    """Write turns as Claude Code JSONL transcript."""
    lines = []
    limit = up_to_turn if up_to_turn is not None else len(turns)
    for turn in turns[:limit]:
        role_type = "human" if turn["role"] == "user" else "assistant"
        lines.append(json.dumps({
            "type": role_type,
            "message": {
                "content": [{"type": "text", "text": turn["text"]}]
            }
        }))
    output_path.write_text("\n".join(lines) + "\n")


def parse_patterns_json(raw_text: str) -> list:
    """Parse patterns from raw LLM output, handling markdown fencing."""
    text = raw_text.strip()
    # Strip markdown fencing
    for prefix in ["```json", "```"]:
        if text.startswith(prefix):
            text = text[len(prefix):]
    if text.endswith("```"):
        text = text[:-3]
    text = text.strip()

    try:
        data = json.loads(text)
        return data.get("patterns", [])
    except json.JSONDecodeError:
        return []


# ============================================================
# CONDITIONS
# ============================================================

def run_soul(scenario: dict, work_dir: Path) -> dict:
    """
    Full SOUL pipeline: invoke conscience.sh turn-by-turn.
    Simulates a real session where the hook fires after each assistant turn.
    """
    soul_dir = setup_soul_dir(work_dir)
    turns = scenario["turns"]
    transcript_path = work_dir / "transcript.jsonl"
    session_id = f"eval-soul-{int(time.time())}"

    # Fake HOME to isolate genome
    fake_home = work_dir / "fakehome"
    fake_home.mkdir(exist_ok=True)
    (fake_home / ".soul" / "genome").mkdir(parents=True, exist_ok=True)

    env = os.environ.copy()
    env["HOME"] = str(fake_home)

    start = time.time()
    hook_calls = 0

    # Walk through turns, calling conscience.sh after each assistant turn
    for i, turn in enumerate(turns):
        # Write transcript up to this turn
        write_transcript(turns, transcript_path, up_to_turn=i + 1)

        if turn["role"] != "assistant":
            continue

        # Build stdin JSON for conscience.sh
        stdin_json = json.dumps({
            "cwd": str(work_dir),
            "last_assistant_message": turn["text"][:500],
            "stop_hook_active": False,
            "session_id": session_id,
            "transcript_path": str(transcript_path),
        })

        try:
            result = subprocess.run(
                [str(soul_dir / "hooks" / "conscience.sh")],
                input=stdin_json,
                capture_output=True,
                text=True,
                timeout=120,
                env=env,
            )
            hook_calls += 1
        except subprocess.TimeoutExpired:
            log(f"  WARNING: conscience.sh timed out on turn {i}")

    elapsed = time.time() - start

    # Collect extracted patterns from:
    # 1. SOUL.md diff
    final_soul = (soul_dir / "SOUL.md").read_text()

    # 2. recent-extractions.jsonl (if it exists)
    extractions_file = soul_dir / "log" / "soul-activity.jsonl"
    extraction_events = []
    if extractions_file.exists():
        for line in extractions_file.read_text().strip().split("\n"):
            if not line:
                continue
            try:
                event = json.loads(line)
                if event.get("event") == "extraction":
                    extraction_events.append(event)
            except json.JSONDecodeError:
                pass

    # 3. Parse patterns from extraction events
    patterns = []
    for event in extraction_events:
        event_patterns = event.get("patterns", [])
        if isinstance(event_patterns, str):
            try:
                event_patterns = json.loads(event_patterns)
            except json.JSONDecodeError:
                event_patterns = []
        patterns.extend(event_patterns)

    # Also diff SOUL.md for any bullets added
    soul_bullets = []
    for line in final_soul.split("\n"):
        line = line.strip()
        if line.startswith("- ") and line not in INITIAL_SOUL:
            soul_bullets.append(line[2:])

    return {
        "condition": "soul",
        "patterns": patterns,
        "soul_bullets": soul_bullets,
        "extraction_events": len(extraction_events),
        "hook_calls": hook_calls,
        "elapsed": round(elapsed, 2),
        "final_soul": final_soul,
    }


def run_append_only(scenario: dict, work_dir: Path) -> dict:
    """
    No real-time detection. At the end, give full transcript to Sonnet
    and ask it to find patterns in one shot.
    """
    turns = scenario["turns"]
    transcript_text = "\n".join(
        f"{'USER' if t['role'] == 'user' else 'ASSISTANT'}: {t['text']}"
        for t in turns
    )

    prompt = EXTRACTION_PROMPT.format(transcript=transcript_text)
    start = time.time()
    result, _ = claude_call(prompt, MODEL_IDS["sonnet"])
    elapsed = time.time() - start

    patterns = parse_patterns_json(result)

    return {
        "condition": "append-only",
        "patterns": patterns,
        "soul_bullets": [],
        "extraction_events": 1 if patterns else 0,
        "hook_calls": 0,
        "elapsed": round(elapsed, 2),
    }


def run_keyword_only(scenario: dict, work_dir: Path) -> dict:
    """
    Tier 1 only: keyword scan with no Haiku confirmation gate.
    Every keyword match triggers Sonnet extraction on the recent window.
    """
    turns = scenario["turns"]
    keywords = [
        "no", "don't", "stop", "instead", "wrong", "not what I",
        "I prefer", "always use", "never use", "I already told",
    ]

    all_patterns = []
    start = time.time()
    extraction_count = 0

    for i, turn in enumerate(turns):
        if turn["role"] != "user":
            continue

        # Tier 1: keyword scan
        lower_text = turn["text"].lower()
        matched = any(kw.lower() in lower_text for kw in keywords)
        if not matched:
            continue

        # Keyword hit -> extract from the window around this turn
        window_start = max(0, i - 4)
        window_end = min(len(turns), i + 3)
        window_text = "\n".join(
            f"{'USER' if t['role'] == 'user' else 'ASSISTANT'}: {t['text']}"
            for t in turns[window_start:window_end]
        )

        prompt = EXTRACTION_PROMPT.format(transcript=window_text)
        result, _ = claude_call(prompt, MODEL_IDS["sonnet"])
        patterns = parse_patterns_json(result)
        all_patterns.extend(patterns)
        extraction_count += 1

    elapsed = time.time() - start

    return {
        "condition": "keyword-only",
        "patterns": all_patterns,
        "soul_bullets": [],
        "extraction_events": extraction_count,
        "hook_calls": 0,
        "elapsed": round(elapsed, 2),
    }


def run_single_shot(scenario: dict, work_dir: Path) -> dict:
    """
    Skip all tiers. Give full transcript to Sonnet in one shot.
    Same as append-only but framed as single-pass extraction.
    """
    # This is functionally identical to append-only but the distinction matters
    # for the paper: append-only = "raw context is sufficient" hypothesis,
    # single-shot = "LLM extraction without tiered detection" hypothesis.
    result = run_append_only(scenario, work_dir)
    result["condition"] = "single-shot"
    return result


CONDITIONS = {
    "soul": run_soul,
    "append-only": run_append_only,
    "keyword-only": run_keyword_only,
    "single-shot": run_single_shot,
}


# ============================================================
# SCORING (inline for quick results; full scorer in extraction_scorer.py)
# ============================================================

def score_results(results: dict, scenario: dict) -> dict:
    """Quick score: match extracted patterns against expected."""
    expected = scenario.get("expected_patterns", [])
    extracted = results.get("patterns", []) + [
        {"summary": b, "type": "unknown", "scope": "unknown"}
        for b in results.get("soul_bullets", [])
    ]

    true_positives = []
    false_negatives = []
    false_positives_caught = []
    false_positives_missed = []

    for exp in expected:
        if not exp.get("should_extract", True):
            # This is a false positive trap
            matched = any(
                any(kw.lower() in (e.get("summary", "") + " " + e.get("detail", "")).lower()
                    for kw in exp["keywords"])
                for e in extracted
            )
            if matched:
                false_positives_missed.append(exp["id"])
            else:
                false_positives_caught.append(exp["id"])
            continue

        # This is a real pattern — check if it was extracted
        matched = any(
            any(kw.lower() in (e.get("summary", "") + " " + e.get("detail", "")).lower()
                for kw in exp["keywords"])
            for e in extracted
        )
        if matched:
            true_positives.append(exp["id"])
        else:
            false_negatives.append(exp["id"])

    n_real = sum(1 for e in expected if e.get("should_extract", True))
    n_traps = sum(1 for e in expected if not e.get("should_extract", True))

    recall = len(true_positives) / n_real if n_real > 0 else 1.0
    fp_rate = len(false_positives_missed) / n_traps if n_traps > 0 else 0.0

    # Precision: true positives out of total extracted
    # (approximate — we count unmatched extractions as false positives)
    total_extracted = len(extracted)
    precision = len(true_positives) / total_extracted if total_extracted > 0 else 1.0
    f1 = 2 * precision * recall / (precision + recall) if (precision + recall) > 0 else 0.0

    return {
        "recall": round(recall, 3),
        "precision": round(precision, 3),
        "f1": round(f1, 3),
        "fp_rate": round(fp_rate, 3),
        "true_positives": true_positives,
        "false_negatives": false_negatives,
        "false_positives_caught": false_positives_caught,
        "false_positives_missed": false_positives_missed,
        "total_extracted": total_extracted,
        "extraction_calls": results.get("extraction_events", 0),
        "elapsed": results.get("elapsed", 0),
    }


# ============================================================
# MAIN
# ============================================================

def run_scenario(scenario_dir: Path, conditions: list[str]):
    """Run specified conditions on a scenario and print results."""
    with open(scenario_dir / "scenario.json") as f:
        scenario = json.load(f)

    # Generate transcript if not present
    transcript_path = scenario_dir / "transcript.jsonl"
    if not transcript_path.exists():
        from generate_transcripts import generate_transcript
        generate_transcript(scenario_dir)

    name = scenario.get("scenario", scenario_dir.name)
    difficulty = scenario.get("difficulty", "?")
    n_expected = len(scenario.get("expected_patterns", []))
    n_traps = sum(
        1 for p in scenario.get("expected_patterns", [])
        if not p.get("should_extract", True)
    )

    log(f"\n{'=' * 60}")
    log(f"Scenario: {name} (L{difficulty})")
    log(f"Expected patterns: {n_expected - n_traps} real, {n_traps} traps")
    log(f"{'=' * 60}")

    all_scores = {}

    for condition in conditions:
        log(f"\n--- {condition} ---")
        work_dir = Path(tempfile.mkdtemp(prefix=f"soul-eval-{condition}-"))

        try:
            runner = CONDITIONS[condition]
            results = runner(scenario, work_dir)
            scores = score_results(results, scenario)
            all_scores[condition] = scores

            log(f"  Extracted: {scores['total_extracted']} patterns")
            log(f"  Recall: {scores['recall']:.0%}  |  Precision: {scores['precision']:.0%}  |  F1: {scores['f1']:.0%}")
            if n_traps > 0:
                log(f"  FP rate: {scores['fp_rate']:.0%} ({len(scores['false_positives_missed'])}/{n_traps} traps triggered)")
            log(f"  Extraction calls: {scores['extraction_calls']}  |  Time: {scores['elapsed']}s")

            if scores["false_negatives"]:
                log(f"  MISSED: {scores['false_negatives']}")
            if scores["false_positives_missed"]:
                log(f"  FALSE POSITIVES: {scores['false_positives_missed']}")

        finally:
            shutil.rmtree(work_dir, ignore_errors=True)

    # Save results
    result_dir = RESULTS_DIR / scenario_dir.name
    result_dir.mkdir(parents=True, exist_ok=True)
    (result_dir / "scores.json").write_text(json.dumps(all_scores, indent=2))
    log(f"\nResults saved to {result_dir / 'scores.json'}")

    return all_scores


def print_summary_table(all_results: dict):
    """Print a comparison table across all scenarios and conditions."""
    log(f"\n{'=' * 80}")
    log("SUMMARY")
    log(f"{'=' * 80}")
    log(f"{'Scenario':<25} {'Condition':<15} {'Recall':>8} {'Precision':>10} {'F1':>6} {'FP Rate':>8} {'Time':>6}")
    log("-" * 80)

    for scenario_name, scores in sorted(all_results.items()):
        for condition, s in sorted(scores.items()):
            log(
                f"{scenario_name:<25} {condition:<15} "
                f"{s['recall']:>7.0%} {s['precision']:>9.0%} "
                f"{s['f1']:>5.0%} {s['fp_rate']:>7.0%} "
                f"{s['elapsed']:>5.1f}s"
            )
        log("")


def main():
    parser = argparse.ArgumentParser(description="SOUL Extraction Eval")
    parser.add_argument(
        "--scenario",
        help="Scenario name (e.g., L1-obvious) or 'all'",
    )
    parser.add_argument(
        "--condition",
        default="all",
        help="Condition to run (soul, append-only, keyword-only, single-shot, or all)",
    )
    parser.add_argument("--all", action="store_true", help="Run all scenarios")
    args = parser.parse_args()

    if args.condition == "all":
        conditions = list(CONDITIONS.keys())
    else:
        conditions = [args.condition]

    if args.all or args.scenario == "all":
        scenario_dirs = sorted([
            d for d in SCENARIOS_DIR.iterdir()
            if d.is_dir() and (d / "scenario.json").exists()
        ])
    elif args.scenario:
        scenario_dirs = [SCENARIOS_DIR / args.scenario]
    else:
        parser.error("Specify --scenario <name> or --all")

    all_results = {}
    for scenario_dir in scenario_dirs:
        if not (scenario_dir / "scenario.json").exists():
            log(f"SKIP: {scenario_dir.name} (no scenario.json)")
            continue
        scores = run_scenario(scenario_dir, conditions)
        all_results[scenario_dir.name] = scores

    if len(all_results) > 1:
        print_summary_table(all_results)


if __name__ == "__main__":
    main()
