#!/usr/bin/env python3
"""
SOUL Extraction Scorer — Scores extracted patterns against ground truth.

Standalone scorer that can be run after extraction_eval.py, or used
to re-score with different matching criteria.

Usage:
    python extraction_scorer.py results/extraction/L1-obvious/scores.json \
        scenarios/extraction/L1-obvious/scenario.json

    python extraction_scorer.py --results-dir results/extraction/
"""

import json
import argparse
from pathlib import Path


def match_pattern(extracted: dict, expected: dict) -> bool:
    """Check if an extracted pattern matches an expected pattern via keyword overlap."""
    text = " ".join([
        extracted.get("summary", ""),
        extracted.get("detail", ""),
        extracted.get("source", ""),
    ]).lower()

    return any(kw.lower() in text for kw in expected.get("keywords", []))


def score_condition(extracted_patterns: list, expected_patterns: list) -> dict:
    """
    Score a single condition's extracted patterns against ground truth.

    Returns precision, recall, F1, false positive rate, and per-pattern details.
    """
    real_expected = [p for p in expected_patterns if p.get("should_extract", True)]
    trap_expected = [p for p in expected_patterns if not p.get("should_extract", True)]

    # Match real patterns (recall)
    matched_real = []
    missed_real = []
    for exp in real_expected:
        if any(match_pattern(ext, exp) for ext in extracted_patterns):
            matched_real.append(exp["id"])
        else:
            missed_real.append(exp["id"])

    # Check false positive traps (FP rate)
    traps_triggered = []
    traps_avoided = []
    for trap in trap_expected:
        if any(match_pattern(ext, trap) for ext in extracted_patterns):
            traps_triggered.append(trap["id"])
        else:
            traps_avoided.append(trap["id"])

    # Compute metrics
    n_real = len(real_expected)
    n_traps = len(trap_expected)
    n_extracted = len(extracted_patterns)

    recall = len(matched_real) / n_real if n_real > 0 else 1.0
    precision = len(matched_real) / n_extracted if n_extracted > 0 else 1.0
    f1 = 2 * precision * recall / (precision + recall) if (precision + recall) > 0 else 0.0
    fp_rate = len(traps_triggered) / n_traps if n_traps > 0 else 0.0

    # Scope accuracy (for matched patterns only)
    scope_correct = 0
    scope_total = 0
    for exp in real_expected:
        if exp["id"] not in matched_real:
            continue
        expected_scope = exp.get("scope")
        if not expected_scope:
            continue
        for ext in extracted_patterns:
            if match_pattern(ext, exp):
                scope_total += 1
                if ext.get("scope") == expected_scope:
                    scope_correct += 1
                break

    scope_accuracy = scope_correct / scope_total if scope_total > 0 else None

    return {
        "recall": round(recall, 3),
        "precision": round(precision, 3),
        "f1": round(f1, 3),
        "fp_rate": round(fp_rate, 3),
        "scope_accuracy": round(scope_accuracy, 3) if scope_accuracy is not None else None,
        "matched": matched_real,
        "missed": missed_real,
        "traps_triggered": traps_triggered,
        "traps_avoided": traps_avoided,
        "total_extracted": n_extracted,
        "total_expected_real": n_real,
        "total_traps": n_traps,
    }


def print_scores(scenario_name: str, condition: str, scores: dict):
    """Print a single condition's scores."""
    print(f"\n--- {scenario_name} / {condition} ---")
    print(f"  Patterns: {scores['total_extracted']} extracted, "
          f"{scores['total_expected_real']} expected")
    print(f"  Recall:    {scores['recall']:.0%} "
          f"({len(scores['matched'])}/{scores['total_expected_real']})")
    print(f"  Precision: {scores['precision']:.0%}")
    print(f"  F1:        {scores['f1']:.0%}")

    if scores['total_traps'] > 0:
        print(f"  FP rate:   {scores['fp_rate']:.0%} "
              f"({len(scores['traps_triggered'])}/{scores['total_traps']} traps hit)")

    if scores['scope_accuracy'] is not None:
        print(f"  Scope acc: {scores['scope_accuracy']:.0%}")

    if scores['missed']:
        print(f"  Missed:    {scores['missed']}")
    if scores['traps_triggered']:
        print(f"  FP traps:  {scores['traps_triggered']}")


def main():
    parser = argparse.ArgumentParser(description="SOUL Extraction Scorer")
    parser.add_argument(
        "scores_file",
        nargs="?",
        help="Path to scores.json from extraction_eval.py",
    )
    parser.add_argument(
        "scenario_file",
        nargs="?",
        help="Path to scenario.json with ground truth",
    )
    parser.add_argument(
        "--results-dir",
        help="Score all results in a directory",
    )
    args = parser.parse_args()

    if args.results_dir:
        results_dir = Path(args.results_dir)
        scenarios_dir = Path(__file__).parent / "scenarios" / "extraction"

        for score_file in sorted(results_dir.glob("*/scores.json")):
            scenario_name = score_file.parent.name
            scenario_file = scenarios_dir / scenario_name / "scenario.json"
            if not scenario_file.exists():
                print(f"SKIP: {scenario_name} (no scenario.json)")
                continue

            with open(scenario_file) as f:
                scenario = json.load(f)
            with open(score_file) as f:
                all_scores = json.load(f)

            for condition, scores in sorted(all_scores.items()):
                print_scores(scenario_name, condition, scores)
    elif args.scores_file and args.scenario_file:
        with open(args.scores_file) as f:
            all_scores = json.load(f)
        with open(args.scenario_file) as f:
            scenario = json.load(f)

        scenario_name = scenario.get("scenario", Path(args.scenario_file).parent.name)
        for condition, scores in sorted(all_scores.items()):
            print_scores(scenario_name, condition, scores)
    else:
        parser.error("Provide scores_file + scenario_file, or --results-dir")


if __name__ == "__main__":
    main()
