#!/usr/bin/env python3
"""
SuccessionBench Experiment 1: Multi-Turn Instruction Drift

Tests whether instruction compliance degrades as context grows in multi-turn
sessions. Uses `claude -p --resume` for real multi-turn conversations.

Conditions:
  A: CLAUDE.md with rules, no hooks (--bare + --system-prompt-file)
  B: CLAUDE.md with rules + Succession hooks active
  C: System prompt on turn 1 only, --bare, no re-injection
  D: No rules at all (naked baseline)

Usage:
    python drift_multiturn.py --model haiku --condition A --turns 30
    python drift_multiturn.py --model haiku --condition A --turns 5 --dry-run
    python drift_multiturn.py --model haiku --all-conditions --turns 30 --reps 3
"""

import argparse
import copy
import json
import sys
from pathlib import Path

# Add parent to path for common imports
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from common.config import (
    BEHAVIORAL_RULES, BEHAVIORAL_RULES_TEXT, CLAUDE_MD_TEMPLATE,
)
from common.harness import (
    SessionConfig, ProjectFixture, DEFAULT_SOURCE_FILES,
    run_turn, append_jsonl, log,
)
from common.scorer import score_all, compliance_summary


PROBE_INTERVAL = 5  # Insert a compliance probe every N turns


def load_tasks():
    """Load filler tasks and compliance probes."""
    tasks_path = Path(__file__).parent / "tasks.json"
    probes_path = Path(__file__).parent / "probes.json"

    with open(tasks_path) as f:
        filler_tasks = json.load(f)["filler_tasks"]
    with open(probes_path) as f:
        probes = json.load(f)["probes"]

    return filler_tasks, probes


def build_turn_sequence(total_turns: int, filler_tasks: list, probes: list) -> list[dict]:
    """Build the sequence of turns, interleaving filler tasks with probes.

    Returns list of dicts: {"turn": N, "prompt": str, "is_probe": bool, "probe_id": str|None}
    """
    sequence = []
    probe_idx = 0
    filler_idx = 0

    for turn in range(total_turns):
        if turn > 0 and turn % PROBE_INTERVAL == 0:
            probe = probes[probe_idx % len(probes)]
            sequence.append({
                "turn": turn,
                "prompt": probe["prompt"],
                "is_probe": True,
                "probe_id": probe["id"],
                "rules_tested": probe.get("rules_tested", []),
            })
            probe_idx += 1
        else:
            task = filler_tasks[filler_idx % len(filler_tasks)]
            sequence.append({
                "turn": turn,
                "prompt": task,
                "is_probe": False,
                "probe_id": None,
                "rules_tested": [],
            })
            filler_idx += 1

    return sequence


def make_session_config(condition: str, model: str, project_dir: str,
                        dry_run: bool, rules_text: str) -> SessionConfig:
    """Create SessionConfig for a given condition.

    Hooks/CLAUDE.md are controlled via project fixture contents, not --bare
    (which requires ANTHROPIC_API_KEY and breaks OAuth).
    - Conditions A/C/D: no .claude/CLAUDE.md in fixture → Claude Code won't find rules
    - Condition A: rules passed via --system-prompt (re-injected every turn by CLI)
    - Condition B: rules in .claude/CLAUDE.md in fixture (auto-discovered by Claude Code)
    - Condition C: rules via --system-prompt on turn 1 only (cleared after)
    - Condition D: no rules anywhere
    """
    if condition == "A":
        # Rules via --system-prompt (always re-sent), no CLAUDE.md in fixture
        return SessionConfig(
            model=model,
            project_dir=project_dir,
            system_prompt=CLAUDE_MD_TEMPLATE.format(rules=rules_text),
            dry_run=dry_run,
        )
    elif condition == "B":
        # Rules in .claude/CLAUDE.md in fixture (auto-discovered + hooks active)
        return SessionConfig(
            model=model,
            project_dir=project_dir,
            dry_run=dry_run,
        )
    elif condition == "C":
        # Rules via --system-prompt on turn 1 only, cleared after
        return SessionConfig(
            model=model,
            project_dir=project_dir,
            system_prompt=CLAUDE_MD_TEMPLATE.format(rules=rules_text),
            dry_run=dry_run,
        )
    elif condition == "D":
        # No rules at all
        return SessionConfig(
            model=model,
            project_dir=project_dir,
            dry_run=dry_run,
        )
    else:
        raise ValueError(f"Unknown condition: {condition}")


def run_condition(condition: str, model: str, total_turns: int,
                  output_dir: Path, rep: int, dry_run: bool):
    """Run a single condition of the drift experiment."""
    rules_text = BEHAVIORAL_RULES_TEXT
    filler_tasks, probes = load_tasks()
    sequence = build_turn_sequence(total_turns, filler_tasks, probes)

    # Set up CLAUDE.md content for conditions A and B
    claude_md = CLAUDE_MD_TEMPLATE.format(rules=rules_text) if condition in ("A", "B") else None

    output_file = output_dir / f"condition-{condition}_rep-{rep}.jsonl"
    log(f"=== Condition {condition}, Rep {rep}, {total_turns} turns ===")
    log(f"Output: {output_file}")

    with ProjectFixture(
        claude_md_content=claude_md,
        source_files=DEFAULT_SOURCE_FILES,
    ) as fixture:
        config = make_session_config(condition, model, fixture.dir, dry_run, rules_text)
        session_id = None

        for step in sequence:
            turn = step["turn"]

            # Condition C: only inject system prompt on turn 1
            if condition == "C" and turn > 0:
                config.system_prompt = None

            log(f"  Turn {turn}: {'[PROBE]' if step['is_probe'] else '[FILLER]'} "
                f"{step['prompt'][:60]}...")

            turn_result = run_turn(
                step["prompt"], turn, config, session_id
            )
            session_id = turn_result.session_id

            # Score probes
            scores = {}
            if step["is_probe"]:
                raw_scores = score_all(turn_result.response, turn_result.tool_uses)
                scores = compliance_summary(raw_scores)

            record = {
                "condition": condition,
                "rep": rep,
                "turn": turn,
                "is_probe": step["is_probe"],
                "probe_id": step["probe_id"],
                "rules_tested": step["rules_tested"],
                "prompt": step["prompt"],
                "response_length": len(turn_result.response),
                "input_tokens": turn_result.input_tokens,
                "output_tokens": turn_result.output_tokens,
                "latency_s": turn_result.latency_s,
                **scores,
            }
            append_jsonl(str(output_file), record)

            if step["is_probe"]:
                rate = scores.get("compliance_rate", 0)
                log(f"    Compliance: {scores.get('total_compliant', 0)}"
                    f"/{scores.get('total_applicable', 0)} ({rate:.0%})")

    log(f"  Done. Results: {output_file}\n")


def main():
    parser = argparse.ArgumentParser(
        description="SuccessionBench Exp 1: Multi-Turn Instruction Drift"
    )
    parser.add_argument("--model", default="haiku", choices=["haiku", "sonnet", "opus"])
    parser.add_argument("--condition", choices=["A", "B", "C", "D"],
                        help="Run a single condition")
    parser.add_argument("--all-conditions", action="store_true",
                        help="Run all conditions")
    parser.add_argument("--turns", type=int, default=30,
                        help="Total turns per session (default: 30)")
    parser.add_argument("--reps", type=int, default=1,
                        help="Repetitions per condition (default: 1)")
    parser.add_argument("--output-dir", default=None,
                        help="Output directory (default: results/<model>)")
    parser.add_argument("--dry-run", action="store_true",
                        help="Run without API calls")
    args = parser.parse_args()

    if not args.condition and not args.all_conditions:
        parser.error("Specify --condition or --all-conditions")

    output_dir = Path(args.output_dir or f"results/{args.model}")
    output_dir.mkdir(parents=True, exist_ok=True)

    conditions = ["A", "B", "C", "D"] if args.all_conditions else [args.condition]

    log(f"SuccessionBench Exp 1: Multi-Turn Instruction Drift")
    log(f"Model: {args.model}, Turns: {args.turns}, Reps: {args.reps}")
    log(f"Conditions: {conditions}")
    log()

    for condition in conditions:
        for rep in range(args.reps):
            run_condition(
                condition=condition,
                model=args.model,
                total_turns=args.turns,
                output_dir=output_dir,
                rep=rep,
                dry_run=args.dry_run,
            )

    # Print summary across all conditions
    log("=== Summary ===")
    for condition in conditions:
        for rep in range(args.reps):
            result_file = output_dir / f"condition-{condition}_rep-{rep}.jsonl"
            if not result_file.exists():
                continue
            probes = []
            with open(result_file) as f:
                for line in f:
                    record = json.loads(line)
                    if record.get("is_probe"):
                        probes.append(record)

            if probes:
                rates = [p.get("compliance_rate", 0) for p in probes]
                avg = sum(rates) / len(rates)
                log(f"  Condition {condition} Rep {rep}: "
                    f"{len(probes)} probes, avg compliance {avg:.0%}")

                # Show compliance by turn
                for p in probes:
                    rate = p.get("compliance_rate", 0)
                    log(f"    Turn {p['turn']:3d}: {rate:.0%} "
                        f"({p.get('total_compliant', 0)}/{p.get('total_applicable', 0)})")

    log("\nDone.")


if __name__ == "__main__":
    main()
