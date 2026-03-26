#!/usr/bin/env python3
"""
SOUL-Bench Scorer

Scores predictions against ground truth using keyword matching.

Usage:
    python scorer.py results/express-api/soul-sonnet/predictions.json scenarios/express-api/ground-truth.json
"""

import json
import argparse
import sys
from pathlib import Path
from collections import defaultdict


def check_answer(prediction_text, question):
    """Check if prediction matches ground truth using keyword matching."""
    pred_lower = prediction_text.lower()

    # Check if any answer keyword is present
    for kw in question.get("answer_keywords", []):
        if kw.lower() in pred_lower:
            return True

    return False


def check_staleness(prediction_text, question):
    """Check if prediction contains stale keywords (bad — should be absent)."""
    pred_lower = prediction_text.lower()
    stale_found = []
    for kw in question.get("stale_keywords", []):
        if kw.lower() in pred_lower:
            stale_found.append(kw)
    return stale_found


def score_predictions(predictions, questions):
    """Score predictions against ground truth. Returns per-category scores."""
    q_map = {q["id"]: q for q in questions}
    scores = defaultdict(lambda: {"correct": 0, "total": 0, "details": []})

    for pred in predictions:
        qid = pred["id"]
        q = q_map[qid]
        cat = q["category"]
        correct = check_answer(pred["prediction"], q)
        stale = check_staleness(pred["prediction"], q)

        scores[cat]["total"] += 1
        if correct:
            scores[cat]["correct"] += 1

        detail = {
            "id": qid,
            "correct": correct,
            "stale_keywords_found": stale,
            "prediction_preview": pred["prediction"][:100],
        }
        scores[cat]["details"].append(detail)

    # Add overall
    total_correct = sum(s["correct"] for s in scores.values())
    total_questions = sum(s["total"] for s in scores.values())
    scores["overall"] = {
        "correct": total_correct,
        "total": total_questions,
        "details": [],
    }

    # Add percentages
    for cat in scores:
        t = scores[cat]["total"]
        scores[cat]["pct"] = (scores[cat]["correct"] / t * 100) if t > 0 else 0

    return dict(scores)


def check_memory_staleness(memory_text, questions):
    """Check the final memory for stale keywords that should have been pruned."""
    mem_lower = memory_text.lower()
    issues = []
    for q in questions:
        for kw in q.get("stale_keywords", []):
            if kw.lower() in mem_lower:
                issues.append({"question_id": q["id"], "stale_keyword": kw})
    return issues


def main():
    parser = argparse.ArgumentParser(description="SOUL-Bench Scorer")
    parser.add_argument("predictions", help="Path to predictions.json")
    parser.add_argument("ground_truth", help="Path to ground-truth.json")
    parser.add_argument("--memory", help="Path to memory.md for staleness check")
    parser.add_argument("--format", choices=["text", "json"], default="text")
    args = parser.parse_args()

    predictions = json.load(open(args.predictions))
    gt = json.load(open(args.ground_truth))
    questions = gt["questions"]

    scores = score_predictions(predictions, questions)

    if args.format == "json":
        print(json.dumps(scores, indent=2))
    else:
        print("=" * 50)
        print("SOUL-Bench Scores")
        print("=" * 50)
        for cat in ["retention", "contradiction", "staleness", "abstention", "warning", "overall"]:
            if cat in scores:
                s = scores[cat]
                print(f"  {cat:15s}: {s['correct']}/{s['total']} ({s['pct']:.0f}%)")
                for d in s.get("details", []):
                    status = "OK" if d["correct"] else "MISS"
                    stale = f" [STALE: {d['stale_keywords_found']}]" if d.get("stale_keywords_found") else ""
                    print(f"    [{status}] {d['id']}: {d['prediction_preview'][:60]}{stale}")

        # Memory staleness check
        if args.memory:
            memory = Path(args.memory).read_text()
            print(f"\n  Memory size: {len(memory)} chars")
            issues = check_memory_staleness(memory, questions)
            if issues:
                print(f"  Stale keywords in memory ({len(issues)}):")
                for i in issues:
                    print(f"    - {i['question_id']}: '{i['stale_keyword']}' still present")
            else:
                print("  No stale keywords found in memory")


if __name__ == "__main__":
    main()
