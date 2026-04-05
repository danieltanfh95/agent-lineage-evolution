#!/usr/bin/env python3
"""
SuccessionBench: Correction Persistence Test

Tests whether user corrections survive when buried deep in context.

Structure per session (7 turns):
  Turn 0: Padded coding task (establishes 150k context depth)
  Turn 1: Initial task (model produces output naturally)
  Turn 2: User correction (explicit behavioral correction)
  Turn 3: Filler task (pushes correction deeper)
  Turn 4: Filler task (correction now ~50k tokens back)
  Turn 5: Filler task (correction now ~100k tokens back)
  Turn 6: Probe (same type of task — does the correction still apply?)

Conditions:
  A: CLAUDE.md + conversation history (correction lives in history only)
  C: No rules (baseline — correction is just another message)

Usage:
    python correction_persistence.py --model sonnet --all-conditions --reps 3
    python correction_persistence.py --model mimo --cli sheath-openrouter --all-conditions --reps 3
    python correction_persistence.py --dry-run --all-conditions --reps 1
"""

import argparse
import json
import random
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from common.config import BEHAVIORAL_RULES_TEXT, CLAUDE_MD_TEMPLATE, MODEL_IDS
from common.harness import (
    SessionConfig, ProjectFixture, DEFAULT_SOURCE_FILES,
    run_turn, append_jsonl, log,
)
from common.padding import generate_padding_block
from common.scorer import score_all, compliance_summary


def load_probes():
    with open(Path(__file__).parent / 'probes.json') as f:
        return json.load(f)


def run_session(condition: str, scenario: dict, model: str, rep: int,
                output_dir: Path, dry_run: bool, cli_binary: str = 'claude',
                depth: int = 150000):
    """Run a single 7-turn correction persistence session."""
    scenario_id = scenario['id']

    # Build turn sequence
    filler_task_prompt = 'Read src/app.js and add a GET /health endpoint that returns {"status": "ok", "timestamp": Date.now()}.'

    turns = [
        {'turn': 0, 'prompt': filler_task_prompt, 'type': 'filler-padded', 'padded': True},
        {'turn': 1, 'prompt': scenario['initial_task'], 'type': 'initial-task'},
        {'turn': 2, 'prompt': scenario['correction'], 'type': 'correction'},
        {'turn': 3, 'prompt': scenario['filler_tasks'][0], 'type': 'filler'},
        {'turn': 4, 'prompt': scenario['filler_tasks'][1], 'type': 'filler'},
        {'turn': 5, 'prompt': scenario['filler_tasks'][2], 'type': 'filler'},
        {'turn': 6, 'prompt': scenario['probe'], 'type': 'probe',
         'tests': scenario['tests']},
    ]

    # Condition A gets CLAUDE.md with rules, C gets nothing
    rules_text = BEHAVIORAL_RULES_TEXT
    claude_md = CLAUDE_MD_TEMPLATE.format(rules=rules_text) if condition == 'A' else None

    output_file = output_dir / f'scenario-{scenario_id}_cond-{condition}_rep-{rep}.jsonl'
    if output_file.exists():
        output_file.unlink()
    log(f'\n  Session: scenario={scenario_id}, condition={condition}, rep={rep}')

    with ProjectFixture(
        claude_md_content=claude_md,
        succession_rules=[],
        source_files=DEFAULT_SOURCE_FILES,
    ) as fixture:
        perm_mode = 'dontAsk' if cli_binary != 'claude' else 'bypassPermissions'
        config = SessionConfig(
            model=model,
            cli_binary=cli_binary,
            project_dir=fixture.dir,
            permission_mode=perm_mode,
            dry_run=dry_run,
        )
        session_id = None

        for step in turns:
            prompt = step['prompt']

            # Apply padding to first turn only
            if step.get('padded') and depth > 0:
                padding = generate_padding_block(depth, rep)
                prompt = f'{padding}\n\n{prompt}'

            turn_result = run_turn(prompt, step['turn'], config, session_id)
            session_id = turn_result.session_id

            # Score probe turn only
            scores = {}
            intended_rules = step.get('tests', [])
            if step['type'] == 'probe' and intended_rules:
                raw_scores = score_all(turn_result.response, turn_result.tool_uses_executed,
                                       full_text=turn_result.full_text)
                raw_scores = {k: v for k, v in raw_scores.items() if k in intended_rules}
                scores = compliance_summary(raw_scores)

            record = {
                'experiment': 'correction-persistence',
                'scenario': scenario_id,
                'condition': condition,
                'rep': rep,
                'turn': step['turn'],
                'turn_type': step['type'],
                'prompt_length': len(prompt),
                'response_length': len(turn_result.response),
                'input_tokens': turn_result.input_tokens,
                'output_tokens': turn_result.output_tokens,
                'cache_creation_input_tokens': turn_result.cache_creation_input_tokens,
                'cache_read_input_tokens': turn_result.cache_read_input_tokens,
                'latency_s': turn_result.latency_s,
                'raw_output': json.loads(turn_result.raw_output) if turn_result.raw_output else None,
                **scores,
            }
            append_jsonl(str(output_file), record)

            status_parts = [f"Turn {step['turn']} [{step['type']}]"]
            rate = scores.get('compliance_rate')
            if rate is not None:
                status_parts.append(
                    f"compliance={scores.get('total_compliant', 0)}"
                    f"/{scores.get('total_applicable', 0)} ({rate:.0%})")
            total = turn_result.input_tokens + turn_result.cache_creation_input_tokens + turn_result.cache_read_input_tokens
            status_parts.append(f'in={turn_result.input_tokens:,} cache={turn_result.cache_read_input_tokens:,} total={total:,}')
            log(f"    {' | '.join(status_parts)}")


def print_summary(output_dir: Path):
    """Print summary table across all sessions."""
    results = {}

    for f in sorted(output_dir.glob('scenario-*.jsonl')):
        with open(f) as fh:
            for line in fh:
                r = json.loads(line)
                if r.get('turn_type') == 'probe':
                    key = (r['scenario'], r['condition'])
                    if key not in results:
                        results[key] = []
                    rate = r.get('compliance_rate')
                    if rate is not None:
                        results[key].append(rate)

    if not results:
        log('  (no results)')
        return

    scenarios = sorted(set(k[0] for k in results))
    conditions = sorted(set(k[1] for k in results))

    log(f"\n{'Scenario':>20}  " + '  '.join(f'Cond {c:>6}' for c in conditions))
    log('-' * (22 + 10 * len(conditions)))

    for scenario in scenarios:
        parts = [f'{scenario:>20}']
        for cond in conditions:
            rates = results.get((scenario, cond), [])
            if rates:
                avg = sum(rates) / len(rates)
                parts.append(f'  {avg:>6.0%}')
            else:
                parts.append(f"  {'N/A':>6}")
        log(''.join(parts))


def main():
    parser = argparse.ArgumentParser(
        description='SuccessionBench: Correction Persistence Test'
    )
    parser.add_argument('--model', default='sonnet',
                        help='Model alias or ID (default: sonnet)')
    parser.add_argument('--cli', default='claude',
                        help='CLI binary: claude or sheath-openrouter (default: claude)')
    parser.add_argument('--condition', choices=['A', 'C'])
    parser.add_argument('--all-conditions', action='store_true')
    parser.add_argument('--scenario', default=None,
                        help='Run a specific scenario ID only')
    parser.add_argument('--depth', type=int, default=150000,
                        help='Token depth for padding (default: 150000)')
    parser.add_argument('--reps', type=int, default=3)
    parser.add_argument('--output-dir', default=None)
    parser.add_argument('--dry-run', action='store_true')
    args = parser.parse_args()

    if not args.condition and not args.all_conditions:
        parser.error('Specify --condition or --all-conditions')

    conditions = ['A', 'C'] if args.all_conditions else [args.condition]

    output_dir = Path(args.output_dir or f'results/{args.model}')
    output_dir.mkdir(parents=True, exist_ok=True)

    probes_data = load_probes()
    scenarios = probes_data['correction_scenarios']

    if args.scenario:
        scenarios = [s for s in scenarios if s['id'] == args.scenario]
        if not scenarios:
            parser.error(f'Unknown scenario: {args.scenario}')

    total_sessions = len(scenarios) * len(conditions) * args.reps
    log('SuccessionBench: Correction Persistence Test')
    log(f'Model: {args.model} (cli: {args.cli})')
    log(f'Depth: {args.depth}')
    log(f'Conditions: {conditions}')
    log(f'Scenarios: {[s["id"] for s in scenarios]}')
    log(f'Reps: {args.reps}')
    log(f'Sessions: {total_sessions}')

    for scenario in scenarios:
        for condition in conditions:
            for rep in range(args.reps):
                run_session(condition, scenario, args.model, rep,
                            output_dir, args.dry_run, cli_binary=args.cli,
                            depth=args.depth)

    log('\n=== Summary ===')
    print_summary(output_dir)
    log('\nDone.')


if __name__ == '__main__':
    main()
