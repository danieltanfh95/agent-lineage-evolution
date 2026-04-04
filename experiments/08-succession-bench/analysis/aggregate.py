#!/usr/bin/env python3
"""
SuccessionBench Cross-Experiment Analysis

Aggregates results from all experiments into summary tables.

Usage:
    python aggregate.py
    python aggregate.py --exp 01-drift-multiturn
"""

import argparse
import json
import statistics
from pathlib import Path


BENCH_DIR = Path(__file__).resolve().parent.parent
EXPERIMENTS = {
    "01-drift-multiturn": "Instruction Drift",
    "02-reinjection-ab": "Re-injection A/B",
    "03-enforcement-catch": "Enforcement Catch Rate",
    "04-extraction-l1l5": "Extraction L1-L5",
    "05-behavioral-transfer": "Behavioral Transfer",
}


def log(msg=""):
    print(msg, flush=True)


def read_jsonl(path: Path) -> list[dict]:
    records = []
    if not path.exists():
        return records
    with open(path) as f:
        for line in f:
            line = line.strip()
            if line:
                records.append(json.loads(line))
    return records


def analyze_drift(results_dir: Path):
    """Analyze Exp 1: Multi-turn drift results."""
    log("\n" + "=" * 70)
    log("EXPERIMENT 1: MULTI-TURN INSTRUCTION DRIFT")
    log("=" * 70)

    for result_file in sorted(results_dir.rglob("condition-*.jsonl")):
        records = read_jsonl(result_file)
        probes = [r for r in records if r.get("is_probe")]

        if not probes:
            continue

        condition = probes[0].get("condition", "?")
        rep = probes[0].get("rep", 0)

        log(f"\nCondition {condition}, Rep {rep}:")
        log(f"  {'Turn':>6} {'Compliance':>12} {'Detail':>30}")
        log(f"  {'-' * 50}")

        for p in probes:
            rate = p.get("compliance_rate", 0)
            compliant = p.get("total_compliant", 0)
            applicable = p.get("total_applicable", 0)
            log(f"  {p['turn']:>6} {compliant}/{applicable} ({rate:.0%})"
                f"  {p.get('probe_id', '')}")

        # Trend: early probes vs late probes
        if len(probes) >= 4:
            early = probes[:len(probes)//2]
            late = probes[len(probes)//2:]
            early_avg = statistics.mean(p.get("compliance_rate", 0) for p in early)
            late_avg = statistics.mean(p.get("compliance_rate", 0) for p in late)
            delta = late_avg - early_avg
            log(f"\n  Early avg: {early_avg:.0%}, Late avg: {late_avg:.0%}, "
                f"Delta: {delta:+.0%}")
            if delta < -0.1:
                log(f"  >>> DRIFT DETECTED: {abs(delta):.0%} degradation")
            elif delta > 0.1:
                log(f"  >>> IMPROVEMENT: {delta:.0%} (surprising)")
            else:
                log(f"  >>> STABLE: no significant drift")


def analyze_enforcement(results_dir: Path):
    """Analyze Exp 3: Enforcement catch rate results."""
    log("\n" + "=" * 70)
    log("EXPERIMENT 3: ENFORCEMENT CATCH RATE")
    log("=" * 70)

    for condition in ["A", "B"]:
        total_violation_turns = 0
        total_violations = 0
        total_benign_turns = 0
        total_false_positives = 0
        latencies = []

        for result_file in sorted(results_dir.rglob(f"condition-{condition}_*.jsonl")):
            records = read_jsonl(result_file)
            for r in records:
                latencies.append(r.get("latency_s", 0))
                if r.get("is_violation_inducing"):
                    total_violation_turns += 1
                    if r.get("any_violation"):
                        total_violations += 1
                else:
                    total_benign_turns += 1
                    if r.get("any_violation"):
                        total_false_positives += 1

        if total_violation_turns == 0:
            continue

        v_rate = total_violations / total_violation_turns
        fp_rate = total_false_positives / total_benign_turns if total_benign_turns > 0 else 0
        avg_latency = statistics.mean(latencies) if latencies else 0

        label = "No enforcement" if condition == "A" else "Hooks active"
        log(f"\nCondition {condition} ({label}):")
        log(f"  Violation-inducing turns: {total_violation_turns}")
        log(f"  Violations attempted: {total_violations} ({v_rate:.0%})")
        log(f"  Benign turns: {total_benign_turns}")
        log(f"  False positives: {total_false_positives} ({fp_rate:.0%})")
        log(f"  Avg latency: {avg_latency:.1f}s")

        if condition == "B":
            catch_rate = 1 - v_rate
            log(f"\n  >>> Catch rate: {catch_rate:.0%}")
            log(f"  >>> False positive rate: {fp_rate:.0%}")


def analyze_extraction(results_dir: Path):
    """Analyze Exp 4: Extraction L1-L5 results."""
    log("\n" + "=" * 70)
    log("EXPERIMENT 4: EXTRACTION QUALITY L1-L5")
    log("=" * 70)

    log(f"\n{'Scenario':<20} {'Condition':<15} {'Recall':>8} {'Precision':>10} "
        f"{'F1':>6} {'FP Rate':>8}")
    log("-" * 70)

    for scores_file in sorted(results_dir.rglob("scores.json")):
        scenario = scores_file.parent.name
        with open(scores_file) as f:
            scores = json.load(f)

        for condition, s in sorted(scores.items()):
            log(f"{scenario:<20} {condition:<15} {s['recall']:>7.0%} "
                f"{s['precision']:>9.0%} {s['f1']:>5.0%} {s['fp_rate']:>7.0%}")


def analyze_transfer(results_dir: Path):
    """Analyze Exp 5: Behavioral transfer results."""
    log("\n" + "=" * 70)
    log("EXPERIMENT 5: BEHAVIORAL TRANSFER")
    log("=" * 70)

    transfer_file = results_dir / "transfer.jsonl"
    control_file = results_dir / "control.jsonl"

    if not transfer_file.exists() or not control_file.exists():
        log("  Results incomplete")
        return

    transfer = {r["probe_id"]: r for r in read_jsonl(transfer_file)}
    control = {r["probe_id"]: r for r in read_jsonl(control_file)}

    log(f"\n{'Probe':<20} {'Transfer':>12} {'Control':>12} {'Delta':>8}")
    log("-" * 55)

    deltas = []
    for probe_id in transfer:
        t_rate = transfer[probe_id].get("compliance_rate", 0)
        c_rate = control.get(probe_id, {}).get("compliance_rate", 0)
        delta = t_rate - c_rate
        deltas.append(delta)
        log(f"{probe_id:<20} {t_rate:>11.0%} {c_rate:>11.0%} {delta:>+7.0%}")

    if deltas:
        avg_delta = statistics.mean(deltas)
        log("-" * 55)
        log(f"{'AVERAGE DELTA':<20} {'':>12} {'':>12} {avg_delta:>+7.0%}")
        if avg_delta > 0.1:
            log(f"\n>>> TRANSFER EFFECTIVE: +{avg_delta:.0%} avg improvement")
        elif avg_delta > 0:
            log(f"\n>>> MARGINAL TRANSFER: +{avg_delta:.0%} avg improvement")
        else:
            log(f"\n>>> NO TRANSFER EFFECT: {avg_delta:.0%}")


def main():
    parser = argparse.ArgumentParser(description="SuccessionBench Analysis")
    parser.add_argument("--exp", help="Specific experiment to analyze")
    parser.add_argument("--model", default="haiku",
                        help="Model results to analyze (default: haiku)")
    args = parser.parse_args()

    log("SuccessionBench — Cross-Experiment Analysis")
    log(f"Model: {args.model}")

    if args.exp:
        experiments = {args.exp: EXPERIMENTS.get(args.exp, args.exp)}
    else:
        experiments = EXPERIMENTS

    for exp_dir, exp_name in experiments.items():
        results_base = BENCH_DIR / exp_dir / "results"

        # Try model-specific subdirectory first, then just results/
        results_dir = results_base / args.model
        if not results_dir.exists():
            results_dir = results_base

        if not results_dir.exists():
            log(f"\n--- {exp_name}: No results found ---")
            continue

        if "drift" in exp_dir:
            analyze_drift(results_dir)
        elif "enforcement" in exp_dir:
            analyze_enforcement(results_dir)
        elif "extraction" in exp_dir:
            analyze_extraction(results_dir)
        elif "transfer" in exp_dir:
            analyze_transfer(results_dir)
        # reinjection uses same format as drift
        elif "reinjection" in exp_dir:
            analyze_drift(results_dir)

    log("\n" + "=" * 70)
    log("Analysis complete.")


if __name__ == "__main__":
    main()
