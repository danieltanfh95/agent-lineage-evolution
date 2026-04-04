#!/usr/bin/env python3
"""
SuccessionBench Experiment 4: Extraction Quality L1-L5

Wraps the existing extraction_eval.py to run all 5 difficulty levels across
all 4 conditions, updating the 'soul' condition to use Succession's bb-based
extraction pipeline instead of the old .soul/hooks/conscience.sh.

The existing extraction_eval.py has:
  - soul: Full 3-tier pipeline via conscience.sh (SOUL-era, needs update)
  - append-only: Full transcript to Sonnet, extract in one shot
  - keyword-only: Tier 1 only, every keyword hit triggers extraction
  - single-shot: Same as append-only (different framing)

We add:
  - succession: Uses bb-based stop hook for correction detection + extraction

Usage:
    python run_all.py --scenario L1-obvious --condition all
    python run_all.py --all
    python run_all.py --dry-run --all
"""

import argparse
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

# Import from existing extraction_eval
SOUL_BENCH_DIR = Path(__file__).resolve().parent.parent.parent / "06-soul-bench"
sys.path.insert(0, str(SOUL_BENCH_DIR))

from extraction_eval import (
    CONDITIONS as SOUL_CONDITIONS,
    run_scenario,
    print_summary_table,
    score_results,
    parse_patterns_json,
    claude_call,
    write_transcript,
    MODEL_IDS,
    EXTRACTION_PROMPT,
    log,
)

REPO_ROOT = Path(__file__).resolve().parent.parent.parent.parent
BB_DIR = REPO_ROOT / "bb"
SCENARIOS_DIR = SOUL_BENCH_DIR / "scenarios" / "extraction"
RESULTS_DIR = Path(__file__).parent / "results"


def run_succession(scenario: dict, work_dir: Path) -> dict:
    """
    Succession pipeline: uses bb-based stop hook for correction detection.

    The stop hook's correction detection uses:
    1. Keyword scan (tier 1) — fast, regex-based
    2. Haiku semantic confirmation (tier 2) — filters false positives
    3. Sonnet extraction (tier 3) — extracts structured rules

    We simulate this by invoking the bb stop hook on each assistant turn.
    """
    turns = scenario["turns"]
    transcript_path = work_dir / "transcript.jsonl"

    # Set up Succession directory structure
    succession_dir = work_dir / ".succession"
    rules_dir = succession_dir / "rules"
    log_dir = succession_dir / "log"
    compiled_dir = succession_dir / "compiled"
    rules_dir.mkdir(parents=True, exist_ok=True)
    log_dir.mkdir(parents=True, exist_ok=True)
    compiled_dir.mkdir(parents=True, exist_ok=True)

    # Write config
    config = {
        "extraction": {
            "enabled": True,
            "correctionKeywords": [
                "no", "don't", "stop", "instead", "wrong", "not what I",
                "I prefer", "always use", "never use", "I already told",
            ],
        },
    }
    (succession_dir / "config.json").write_text(json.dumps(config, indent=2))

    start = time.time()
    all_patterns = []
    extraction_count = 0

    # Process turn by turn
    for i, turn in enumerate(turns):
        # Write transcript up to this turn
        write_transcript(turns, transcript_path, up_to_turn=i + 1)

        if turn["role"] != "assistant":
            continue

        # Check if the previous user turn has correction keywords
        if i > 0 and turns[i - 1]["role"] == "user":
            user_text = turns[i - 1]["text"].lower()
            keywords = config["extraction"]["correctionKeywords"]
            has_keyword = any(kw.lower() in user_text for kw in keywords)

            if not has_keyword:
                continue

            # Tier 2: Haiku semantic confirmation
            confirm_prompt = (
                f"Does this user message contain a correction, preference, or feedback "
                f"about how the assistant should behave? Answer YES or NO only.\n\n"
                f"USER: {turns[i - 1]['text']}"
            )
            confirm_result, _ = claude_call(confirm_prompt, MODEL_IDS["haiku"])
            if "yes" not in confirm_result.lower():
                continue

            # Tier 3: Sonnet extraction
            window_start = max(0, i - 4)
            window_end = min(len(turns), i + 1)
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
        "condition": "succession",
        "patterns": all_patterns,
        "soul_bullets": [],
        "extraction_events": extraction_count,
        "hook_calls": extraction_count,
        "elapsed": round(elapsed, 2),
    }


# Extended conditions including Succession
ALL_CONDITIONS = {
    **SOUL_CONDITIONS,
    "succession": run_succession,
}


def run_scenario_extended(scenario_dir: Path, conditions: list[str]):
    """Run specified conditions (including succession) on a scenario."""
    with open(scenario_dir / "scenario.json") as f:
        scenario = json.load(f)

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
        work_dir = Path(tempfile.mkdtemp(prefix=f"extraction-{condition}-"))

        try:
            runner = ALL_CONDITIONS[condition]
            results = runner(scenario, work_dir)
            scores = score_results(results, scenario)
            all_scores[condition] = scores

            log(f"  Extracted: {scores['total_extracted']} patterns")
            log(f"  Recall: {scores['recall']:.0%}  |  Precision: {scores['precision']:.0%}  |  F1: {scores['f1']:.0%}")
            if n_traps > 0:
                log(f"  FP rate: {scores['fp_rate']:.0%} ({len(scores['false_positives_missed'])}/{n_traps} traps triggered)")
            log(f"  Extraction calls: {scores['extraction_calls']}  |  Time: {scores['elapsed']}s")

        finally:
            shutil.rmtree(work_dir, ignore_errors=True)

    # Save results
    result_dir = RESULTS_DIR / scenario_dir.name
    result_dir.mkdir(parents=True, exist_ok=True)
    (result_dir / "scores.json").write_text(json.dumps(all_scores, indent=2))
    log(f"\nResults saved to {result_dir / 'scores.json'}")

    return all_scores


def main():
    parser = argparse.ArgumentParser(
        description="SuccessionBench Exp 4: Extraction Quality L1-L5"
    )
    parser.add_argument("--scenario", help="Scenario (e.g. L1-obvious) or 'all'")
    parser.add_argument("--condition", default="all",
                        help="Condition (succession, append-only, keyword-only, single-shot, soul, or all)")
    parser.add_argument("--all", action="store_true", help="Run all scenarios")
    parser.add_argument("--dry-run", action="store_true",
                        help="Print what would be run without making API calls")
    args = parser.parse_args()

    if args.condition == "all":
        conditions = list(ALL_CONDITIONS.keys())
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

    if args.dry_run:
        log("DRY RUN — listing what would be run:")
        for sd in scenario_dirs:
            for c in conditions:
                log(f"  {sd.name} x {c}")
        log(f"\nTotal: {len(scenario_dirs)} scenarios x {len(conditions)} conditions = "
            f"{len(scenario_dirs) * len(conditions)} cells")
        return

    log(f"SuccessionBench Exp 4: Extraction Quality L1-L5")
    log(f"Scenarios: {[sd.name for sd in scenario_dirs]}")
    log(f"Conditions: {conditions}")

    all_results = {}
    for scenario_dir in scenario_dirs:
        if not (scenario_dir / "scenario.json").exists():
            log(f"SKIP: {scenario_dir.name} (no scenario.json)")
            continue
        scores = run_scenario_extended(scenario_dir, conditions)
        all_results[scenario_dir.name] = scores

    if len(all_results) > 1:
        print_summary_table(all_results)

    log("\nDone.")


if __name__ == "__main__":
    main()
