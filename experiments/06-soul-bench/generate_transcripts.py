#!/usr/bin/env python3
"""
Synthetic JSONL Transcript Generator for SOUL Extraction Eval

Reads scenario.json definitions containing annotated turns and outputs
Claude Code JSONL transcript format.

Usage:
    python generate_transcripts.py                          # Generate all scenarios
    python generate_transcripts.py --scenario L1-obvious    # Generate one scenario
"""

import json
import argparse
from pathlib import Path


SCENARIOS_DIR = Path(__file__).parent / "scenarios" / "extraction"


def turn_to_jsonl(role: str, text: str) -> str:
    """Convert a turn to Claude Code transcript JSONL format."""
    if role == "user":
        return json.dumps({
            "type": "human",
            "message": {
                "content": [{"type": "text", "text": text}]
            }
        })
    elif role == "assistant":
        return json.dumps({
            "type": "assistant",
            "message": {
                "content": [{"type": "text", "text": text}]
            }
        })
    else:
        raise ValueError(f"Unknown role: {role}")


def generate_transcript(scenario_path: Path) -> Path:
    """Generate a JSONL transcript from a scenario.json file."""
    with open(scenario_path / "scenario.json") as f:
        scenario = json.load(f)

    output_path = scenario_path / "transcript.jsonl"
    lines = []
    for turn in scenario["turns"]:
        lines.append(turn_to_jsonl(turn["role"], turn["text"]))

    output_path.write_text("\n".join(lines) + "\n")
    return output_path


def main():
    parser = argparse.ArgumentParser(description="Generate synthetic JSONL transcripts")
    parser.add_argument("--scenario", help="Generate only this scenario (e.g., L1-obvious)")
    args = parser.parse_args()

    if args.scenario:
        dirs = [SCENARIOS_DIR / args.scenario]
    else:
        dirs = sorted([
            d for d in SCENARIOS_DIR.iterdir()
            if d.is_dir() and (d / "scenario.json").exists()
        ])

    if not dirs:
        print("No scenarios found with scenario.json files.")
        return

    for scenario_dir in dirs:
        if not (scenario_dir / "scenario.json").exists():
            print(f"  SKIP: {scenario_dir.name} (no scenario.json)")
            continue
        output = generate_transcript(scenario_dir)
        with open(scenario_dir / "scenario.json") as f:
            scenario = json.load(f)
        n_turns = len(scenario["turns"])
        n_patterns = len(scenario.get("expected_patterns", []))
        print(f"  {scenario_dir.name}: {n_turns} turns, {n_patterns} expected patterns -> {output.name}")

    print("\nDone.")


if __name__ == "__main__":
    main()
