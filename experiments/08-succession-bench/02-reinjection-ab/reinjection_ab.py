#!/usr/bin/env python3
"""
SuccessionBench Experiment 2: CLAUDE.md vs Succession Re-injection A/B

Tests whether Succession's periodic re-injection via additionalContext provides
any benefit over CLAUDE.md's always-present injection.

Since CLAUDE.md is re-injected on every `claude -p --resume` call (rebuilt from
disk each invocation), Succession's advisory re-injection may be redundant.

Conditions:
  A: CLAUDE.md only (rules in .claude/CLAUDE.md, bare + system-prompt-file)
     Isolates CLAUDE.md's native always-present behavior.
  B: CLAUDE.md + Succession stop hook re-injecting same rules via additionalContext
     Tests if explicit re-injection adds signal beyond CLAUDE.md.
  C: No CLAUDE.md, Succession re-injection only (rules in .succession/ advisory tier)
     Tests if additionalContext alone is sufficient.
  D: No CLAUDE.md, --append-system-prompt with rules (inject once, never refreshed)
     True "inject once" baseline.

Key result interpretations:
  - A == B → re-injection redundant for CLAUDE.md-resident rules
  - C < A  → CLAUDE.md's always-present design superior to periodic additionalContext
  - D < C  → periodic re-injection beats inject-once

Usage:
    python reinjection_ab.py --model haiku --condition A --turns 30
    python reinjection_ab.py --model haiku --all-conditions --turns 30 --reps 3
    python reinjection_ab.py --dry-run --all-conditions --turns 5
"""

import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from common.config import (
    BEHAVIORAL_RULES_TEXT, CLAUDE_MD_TEMPLATE,
)
from common.harness import (
    SessionConfig, ProjectFixture, DEFAULT_SOURCE_FILES,
    run_turn, append_jsonl, log,
)
from common.scorer import score_all, compliance_summary

# Reuse probe and task data from Exp 1
EXP1_DIR = Path(__file__).resolve().parent.parent / "01-drift-multiturn"

PROBE_INTERVAL = 5


def load_tasks():
    with open(EXP1_DIR / "tasks.json") as f:
        filler_tasks = json.load(f)["filler_tasks"]
    with open(EXP1_DIR / "probes.json") as f:
        probes = json.load(f)["probes"]
    return filler_tasks, probes


def build_turn_sequence(total_turns, filler_tasks, probes):
    """Same interleaving logic as Exp 1."""
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


def make_succession_advisory_rules() -> list[dict]:
    """Create Succession rule files for advisory-tier re-injection."""
    rules = []
    for name, rule in {
        "plan-before-code": "Always start your response with a '## Plan' section before any code.",
        "single-quotes": "In Python code, always use single quotes for strings.",
        "no-rm-rf": "Never use rm -rf in any command.",
        "edit-not-sed": "Always use the Edit tool for source files, never sed or awk.",
    }.items():
        rules.append({
            "name": name,
            "filename": f"{name}.md",
            "content": f"""---
name: {name}
enforcement: advisory
scope: project
reinjection_interval: 10
---

{rule}
""",
        })
    return rules


def make_session_config(condition: str, model: str, project_dir: str,
                        dry_run: bool) -> SessionConfig:
    rules_text = BEHAVIORAL_RULES_TEXT
    claude_md = CLAUDE_MD_TEMPLATE.format(rules=rules_text)

    if condition == "A":
        # Rules via --system-prompt (no CLAUDE.md in fixture, no hooks)
        return SessionConfig(
            model=model,
            project_dir=project_dir,
            system_prompt=claude_md,
            dry_run=dry_run,
        )
    elif condition == "B":
        # CLAUDE.md in fixture + Succession hooks active
        return SessionConfig(
            model=model,
            project_dir=project_dir,
            dry_run=dry_run,
        )
    elif condition == "C":
        # No CLAUDE.md, Succession re-injection only (hooks run via fixture)
        return SessionConfig(
            model=model,
            project_dir=project_dir,
            dry_run=dry_run,
        )
    elif condition == "D":
        # Inject once via append-system-prompt, no re-injection
        return SessionConfig(
            model=model,
            project_dir=project_dir,
            append_system_prompt=claude_md,
            dry_run=dry_run,
        )
    else:
        raise ValueError(f"Unknown condition: {condition}")


def run_condition(condition: str, model: str, total_turns: int,
                  output_dir: Path, rep: int, dry_run: bool):
    rules_text = BEHAVIORAL_RULES_TEXT
    filler_tasks, probes = load_tasks()
    sequence = build_turn_sequence(total_turns, filler_tasks, probes)

    # Condition A/B: CLAUDE.md present. C/D: no CLAUDE.md
    claude_md = (
        CLAUDE_MD_TEMPLATE.format(rules=rules_text) if condition in ("A", "B") else None
    )

    # Condition B/C: Succession advisory rules active
    succession_rules = (
        make_succession_advisory_rules() if condition in ("B", "C") else []
    )

    output_file = output_dir / f"condition-{condition}_rep-{rep}.jsonl"
    log(f"=== Condition {condition}, Rep {rep}, {total_turns} turns ===")
    log(f"  CLAUDE.md: {'yes' if claude_md else 'no'}")
    log(f"  Succession rules: {len(succession_rules)}")
    log(f"  Output: {output_file}")

    with ProjectFixture(
        claude_md_content=claude_md,
        succession_rules=succession_rules,
        source_files=DEFAULT_SOURCE_FILES,
    ) as fixture:
        config = make_session_config(condition, model, fixture.dir, dry_run)
        session_id = None

        for step in sequence:
            turn = step["turn"]

            log(f"  Turn {turn}: {'[PROBE]' if step['is_probe'] else '[FILLER]'} "
                f"{step['prompt'][:60]}...")

            turn_result = run_turn(step["prompt"], turn, config, session_id)
            session_id = turn_result.session_id

            scores = {}
            if step["is_probe"]:
                raw_scores = score_all(turn_result.response, turn_result.tool_uses)
                scores = compliance_summary(raw_scores)

            record = {
                "experiment": "reinjection-ab",
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
        description="SuccessionBench Exp 2: CLAUDE.md vs Re-injection A/B"
    )
    parser.add_argument("--model", default="haiku", choices=["haiku", "sonnet", "opus"])
    parser.add_argument("--condition", choices=["A", "B", "C", "D"])
    parser.add_argument("--all-conditions", action="store_true")
    parser.add_argument("--turns", type=int, default=30)
    parser.add_argument("--reps", type=int, default=1)
    parser.add_argument("--output-dir", default=None)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    if not args.condition and not args.all_conditions:
        parser.error("Specify --condition or --all-conditions")

    output_dir = Path(args.output_dir or f"results/{args.model}")
    output_dir.mkdir(parents=True, exist_ok=True)

    conditions = ["A", "B", "C", "D"] if args.all_conditions else [args.condition]

    log(f"SuccessionBench Exp 2: Re-injection A/B")
    log(f"Model: {args.model}, Turns: {args.turns}, Reps: {args.reps}")
    log(f"Conditions: {conditions}")
    log()

    for condition in conditions:
        for rep in range(args.reps):
            run_condition(condition, args.model, args.turns, output_dir, rep, args.dry_run)

    log("Done.")


if __name__ == "__main__":
    main()
