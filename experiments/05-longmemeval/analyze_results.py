#!/usr/bin/env python3
"""
SOUL-LongMemEval Results Analyzer

Parses metrics JSONL files and generates latency/token/accuracy tables
with per-category breakdowns.

Usage:
    python analyze_results.py results/
    python analyze_results.py results/ --format markdown
"""

import json
import argparse
import sys
from pathlib import Path
from collections import defaultdict

# Question type → category mapping
TYPE_TO_CATEGORY = {
    "single-session-user": "IE",
    "single-session-assistant": "IE",
    "single-session-preference": "IE",
    "multi-session": "MR",
    "knowledge-update": "KU",
    "temporal-reasoning": "TR",
}


def get_category(question_type, question_id):
    """Map question type to evaluation category."""
    if question_id.endswith("_abs"):
        return "ABS"
    return TYPE_TO_CATEGORY.get(question_type, "OTHER")


def load_metrics(results_dir):
    """Load metrics from all condition subdirectories."""
    results_dir = Path(results_dir)
    all_metrics = {}

    for subdir in sorted(results_dir.iterdir()):
        if not subdir.is_dir():
            continue
        metrics_file = subdir / "metrics.jsonl"
        if not metrics_file.exists():
            continue

        condition = subdir.name
        instances = []
        with open(metrics_file) as f:
            for line in f:
                if line.strip():
                    instances.append(json.loads(line))
        all_metrics[condition] = instances

    return all_metrics


def load_eval_results(results_dir):
    """Load LongMemEval evaluation results (if available)."""
    results_dir = Path(results_dir)
    eval_results = {}

    for subdir in sorted(results_dir.iterdir()):
        if not subdir.is_dir():
            continue
        log_file = subdir / "predictions.jsonl.log"
        if not log_file.exists():
            continue

        condition = subdir.name
        results = []
        with open(log_file) as f:
            for line in f:
                if line.strip():
                    results.append(json.loads(line))
        eval_results[condition] = results

    return eval_results


def print_table(header, rows, fmt="text"):
    """Generic table printer."""
    if fmt == "markdown":
        print(f"| {' | '.join(header)} |")
        print(f"| {' | '.join(['---'] * len(header))} |")
        for row in rows:
            print(f"| {' | '.join(row)} |")
    else:
        if not rows:
            return
        widths = [max(len(h), max((len(r[i]) for r in rows), default=0)) for i, h in enumerate(header)]
        fmt_str = "  ".join(f"{{:<{w}}}" for w in widths)
        print(fmt_str.format(*header))
        print(fmt_str.format(*["-" * w for w in widths]))
        for row in rows:
            print(fmt_str.format(*row))


def print_latency_table(all_metrics, fmt="text"):
    """Print latency and compaction summary table."""
    print("\n## Latency & Compaction Summary\n")

    header = ["Condition", "Instances", "Avg Latency", "Total Time", "Avg Compactions", "Avg Memory (chars)"]
    rows = []

    for condition, instances in sorted(all_metrics.items()):
        n = len(instances)
        avg_latency = sum(i["total_latency_s"] for i in instances) / n if n else 0
        total_time = sum(i["total_latency_s"] for i in instances)
        avg_compactions = sum(i["num_compactions"] for i in instances) / n if n else 0
        avg_memory = sum(i["final_memory_size_chars"] for i in instances) / n if n else 0

        rows.append([
            condition,
            str(n),
            f"{avg_latency:.1f}s",
            f"{total_time / 60:.1f}min",
            f"{avg_compactions:.1f}",
            f"{avg_memory:.0f}",
        ])

    print_table(header, rows, fmt)


def print_token_table(all_metrics, fmt="text"):
    """Print token usage summary."""
    print("\n## Token Usage Summary\n")

    header = ["Condition", "Avg Input Tokens", "Avg Output Tokens", "Avg Total Tokens", "Compression Ratio"]
    rows = []

    for condition, instances in sorted(all_metrics.items()):
        n = len(instances)
        avg_input = sum(i["total_input_tokens"] for i in instances) / n if n else 0
        avg_output = sum(i["total_output_tokens"] for i in instances) / n if n else 0
        avg_total = avg_input + avg_output

        avg_memory_chars = sum(i["final_memory_size_chars"] for i in instances) / n if n else 0
        est_memory_tokens = avg_memory_chars / 4  # rough: 1 token ≈ 4 chars
        if est_memory_tokens > 0:
            ratio = f"115k:{est_memory_tokens:.0f}"
        else:
            ratio = "N/A"

        rows.append([
            condition,
            f"{avg_input:,.0f}",
            f"{avg_output:,.0f}",
            f"{avg_total:,.0f}",
            ratio,
        ])

    print_table(header, rows, fmt)


def print_accuracy_table(eval_results, all_metrics, fmt="text"):
    """Print accuracy table with per-category breakdown (requires evaluation results)."""
    if not eval_results:
        print("\n## Accuracy\n")
        print("No evaluation results found. Run LongMemEval evaluation first.")
        print("(Requires OpenAI API key for GPT-4o evaluation)")
        return

    print("\n## Accuracy (LongMemEval Evaluation)\n")

    categories = ["IE", "MR", "KU", "TR", "ABS", "Overall"]
    header = ["Condition"] + categories
    rows = []

    for condition, results in sorted(eval_results.items()):
        type_map = {}
        if condition in all_metrics:
            for inst in all_metrics[condition]:
                type_map[inst["question_id"]] = inst["question_type"]

        cat_correct = defaultdict(int)
        cat_total = defaultdict(int)

        for result in results:
            qid = result["question_id"]
            qtype = type_map.get(qid, result.get("question_type", "unknown"))
            cat = get_category(qtype, qid)
            correct = result.get("autoeval_label", "").lower() in ("correct", "true", "1", "yes")
            cat_correct[cat] += int(correct)
            cat_total[cat] += 1
            cat_correct["Overall"] += int(correct)
            cat_total["Overall"] += 1

        row = [condition]
        for cat in categories:
            total = cat_total.get(cat, 0)
            if total > 0:
                acc = cat_correct[cat] / total * 100
                row.append(f"{acc:.1f}% ({cat_correct[cat]}/{total})")
            else:
                row.append("—")
        rows.append(row)

    print_table(header, rows, fmt)


def print_latency_per_category(all_metrics, fmt="text"):
    """Print average latency broken down by question category."""
    print("\n## Average Latency per Category\n")

    all_cats = set()
    data = {}

    for condition, instances in all_metrics.items():
        data[condition] = defaultdict(list)
        for inst in instances:
            cat = get_category(inst["question_type"], inst["question_id"])
            data[condition][cat].append(inst["total_latency_s"])
            all_cats.add(cat)

    categories = sorted(all_cats)
    header = ["Condition"] + categories
    rows = []

    for condition in sorted(data.keys()):
        row = [condition]
        for cat in categories:
            latencies = data[condition].get(cat, [])
            if latencies:
                avg = sum(latencies) / len(latencies)
                row.append(f"{avg:.1f}s")
            else:
                row.append("—")
        rows.append(row)

    print_table(header, rows, fmt)


def main():
    parser = argparse.ArgumentParser(description="SOUL-LongMemEval Results Analyzer")
    parser.add_argument("results_dir", help="Path to results directory")
    parser.add_argument("--format", choices=["text", "markdown"], default="text", help="Output format")
    args = parser.parse_args()

    results_dir = Path(args.results_dir)
    if not results_dir.exists():
        print(f"Results directory not found: {results_dir}", file=sys.stderr)
        sys.exit(1)

    all_metrics = load_metrics(results_dir)
    if not all_metrics:
        print("No metrics files found. Run the adapter first.", file=sys.stderr)
        sys.exit(1)

    eval_results = load_eval_results(results_dir)

    print("=" * 60)
    print("SOUL-LongMemEval Results Analysis")
    print("=" * 60)

    print_latency_table(all_metrics, args.format)
    print_token_table(all_metrics, args.format)
    print_latency_per_category(all_metrics, args.format)
    print_accuracy_table(eval_results, all_metrics, args.format)

    # Summary
    print("\n## Summary\n")
    for condition, instances in sorted(all_metrics.items()):
        n = len(instances)
        total_time = sum(i["total_latency_s"] for i in instances)
        print(f"{condition}: {n} instances, {total_time / 60:.1f} min total")


if __name__ == "__main__":
    main()
