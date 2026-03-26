#!/usr/bin/env python3
"""
SOUL-LongMemEval Adapter

Processes LongMemEval instances through SOUL's compaction pipeline
and generates predictions for evaluation.

Uses `claude -p` as the LLM harness (works with Max plan, no API key needed).

Usage:
    python adapter.py --config configs/haiku.json --data data/longmemeval_s_cleaned.json
    python adapter.py --config configs/haiku.json --data data/longmemeval_s_cleaned.json --limit 5
    python adapter.py --config configs/no-memory.json --data data/longmemeval_s_cleaned.json
"""

import json
import os
import subprocess
import time
import argparse
import sys
from pathlib import Path

from compaction_prompt import (
    COMPACTION_PROMPT, QA_PROMPT, FULL_CONTEXT_QA_PROMPT,
    EXTRACT_PROMPT, MERGE_PROMPT,
)


def log(msg):
    """Print with immediate flush so output is visible in real-time."""
    print(msg, flush=True)

# Model ID mapping
MODEL_IDS = {
    "haiku": "claude-haiku-4-5-20251001",
    "sonnet": "claude-sonnet-4-5-20250929",
    "sonnet46": "claude-sonnet-4-6",
    "opus": "claude-opus-4-6",
}


SYSTEM_PROMPT = "You are a helpful assistant. Follow the user's instructions precisely. Be concise."


def claude_call(prompt, model_id, max_tokens=2048):
    """Call claude -p with minimal system prompt, no tools.

    Returns (response_text, usage_dict, latency_s).
    """
    start = time.time()
    result = subprocess.run(
        [
            "claude", "-p",
            "--model", model_id,
            "--tools", "",
            "--output-format", "json",
            "--no-session-persistence",
            "--system-prompt", SYSTEM_PROMPT,
        ],
        input=prompt,
        capture_output=True,
        text=True,
        timeout=300,
    )
    elapsed = time.time() - start

    if result.returncode != 0:
        raise RuntimeError(f"claude -p failed (exit {result.returncode}): {result.stderr[:500]}")

    # Parse JSON output — it's an array; the result is in the last entry with type "result"
    data = json.loads(result.stdout)
    response_text = ""
    usage = {"input_tokens": 0, "output_tokens": 0}

    for item in data:
        if item.get("type") == "result":
            response_text = item.get("result", "")
            raw_usage = item.get("usage", {})
            usage["input_tokens"] = raw_usage.get("input_tokens", 0)
            usage["output_tokens"] = raw_usage.get("output_tokens", 0)
            break

    return response_text, usage, round(elapsed, 3)


def format_session(session_turns):
    """Format a list of conversation turns into a readable transcript."""
    lines = []
    for turn in session_turns:
        role = turn["role"].upper()
        content = turn["content"]
        lines.append(f"{role}: {content}")
    return "\n\n".join(lines)


def format_all_sessions(sessions, dates):
    """Format all sessions into a single context string (for full-context condition)."""
    parts = []
    for i, (session, date) in enumerate(zip(sessions, dates)):
        parts.append(f"--- Session {i + 1} (Date: {date}) ---")
        parts.append(format_session(session))
        parts.append("")
    return "\n".join(parts)


def compact_memory(current_memory, session_transcript, timestamp, model_id):
    """Run one compaction cycle. Returns (new_memory, metrics)."""
    prompt = COMPACTION_PROMPT.format(
        current_memory=current_memory,
        session_transcript=session_transcript,
        timestamp=timestamp,
    )
    new_memory, usage, latency = claude_call(prompt, model_id)
    metrics = {
        "type": "compaction",
        "model": model_id,
        "input_tokens": usage["input_tokens"],
        "output_tokens": usage["output_tokens"],
        "latency_s": latency,
        "memory_size_chars": len(new_memory),
    }
    return new_memory, metrics


def extract_facts(session_transcript, timestamp, model_id):
    """Extract structured facts from a session. Returns (facts_text, metrics)."""
    prompt = EXTRACT_PROMPT.format(
        session_transcript=session_transcript,
        timestamp=timestamp,
    )
    facts, usage, latency = claude_call(prompt, model_id)
    metrics = {
        "type": "extract",
        "model": model_id,
        "input_tokens": usage["input_tokens"],
        "output_tokens": usage["output_tokens"],
        "latency_s": latency,
        "facts_size_chars": len(facts),
    }
    return facts, metrics


def merge_facts(current_memory, extracted_facts, timestamp, model_id):
    """Merge extracted facts into memory. Returns (new_memory, metrics)."""
    prompt = MERGE_PROMPT.format(
        current_memory=current_memory,
        extracted_facts=extracted_facts,
        timestamp=timestamp,
    )
    new_memory, usage, latency = claude_call(prompt, model_id)
    metrics = {
        "type": "merge",
        "model": model_id,
        "input_tokens": usage["input_tokens"],
        "output_tokens": usage["output_tokens"],
        "latency_s": latency,
        "memory_size_chars": len(new_memory),
    }
    return new_memory, metrics


def answer_question(memory, question, question_date, model_id):
    """Answer a LongMemEval question using compacted memory as context."""
    prompt = QA_PROMPT.format(
        memory=memory,
        question_date=question_date,
        question=question,
    )
    answer, usage, latency = claude_call(prompt, model_id, max_tokens=512)
    metrics = {
        "type": "qa",
        "model": model_id,
        "input_tokens": usage["input_tokens"],
        "output_tokens": usage["output_tokens"],
        "latency_s": latency,
    }
    return answer, metrics


def answer_question_no_memory(question, question_date, model_id):
    """Answer without any memory context (no-memory baseline)."""
    prompt = (
        f"The current date is {question_date}.\n\n"
        f"USER QUESTION: {question}\n\n"
        f'Answer the question. If you don\'t have enough information, say "I don\'t know."\n'
        f"Be specific and concise."
    )
    answer, usage, latency = claude_call(prompt, model_id, max_tokens=512)
    metrics = {
        "type": "qa",
        "model": model_id,
        "input_tokens": usage["input_tokens"],
        "output_tokens": usage["output_tokens"],
        "latency_s": latency,
    }
    return answer, metrics


def answer_question_full_context(sessions, dates, question, question_date, model_id):
    """Answer with full conversation history in context (oracle upper bound)."""
    full_context = format_all_sessions(sessions, dates)
    prompt = FULL_CONTEXT_QA_PROMPT.format(
        full_context=full_context,
        question_date=question_date,
        question=question,
    )
    answer, usage, latency = claude_call(prompt, model_id, max_tokens=512)
    metrics = {
        "type": "qa",
        "model": model_id,
        "input_tokens": usage["input_tokens"],
        "output_tokens": usage["output_tokens"],
        "latency_s": latency,
    }
    return answer, metrics


def process_instance(instance, config):
    """Process one LongMemEval instance through the configured pipeline.

    Returns (answer, final_memory, metrics_list).
    """
    condition = config["condition"]
    reader_model_id = MODEL_IDS[config["reader_model"]]

    question = instance["question"]
    question_date = instance.get("question_date", "unknown")
    sessions = instance["haystack_sessions"]
    dates = instance.get("haystack_dates", ["unknown"] * len(sessions))

    # --- No-memory baseline ---
    if condition == "no-memory":
        answer, qa_metrics = answer_question_no_memory(
            question, question_date, reader_model_id
        )
        return answer, "", [qa_metrics]

    # --- Full-context baseline ---
    if condition == "full-context":
        answer, qa_metrics = answer_question_full_context(
            sessions, dates, question, question_date, reader_model_id
        )
        return answer, "(full context)", [qa_metrics]

    # --- SOUL compaction conditions ---
    compact_model_id = MODEL_IDS[config["compact_model"]]
    compact_every = config.get("compact_every", 1)
    use_extract = config.get("extract", False)
    extract_model_id = MODEL_IDS.get(config.get("extract_model", "haiku"), MODEL_IDS["haiku"])

    memory = "# Memory\n\nNo conversations yet."
    all_metrics = []
    pending_transcript = ""

    for i, session in enumerate(sessions):
        transcript = format_session(session)
        date = dates[i] if i < len(dates) else "unknown"

        # Accumulate transcript for batch compaction
        if pending_transcript:
            pending_transcript += f"\n\n--- Session (Date: {date}) ---\n\n{transcript}"
        else:
            pending_transcript = f"--- Session (Date: {date}) ---\n\n{transcript}"

        # Compact at configured frequency
        should_compact_now = (i + 1) % compact_every == 0 or i == len(sessions) - 1
        if should_compact_now and pending_transcript:
            if use_extract:
                # Two-stage: extract facts, then merge into memory
                facts, ext_metrics = extract_facts(pending_transcript, date, extract_model_id)
                all_metrics.append(ext_metrics)
                log(f"    extract {i+1}/{len(sessions)}: {ext_metrics['latency_s']:.1f}s, facts={ext_metrics['facts_size_chars']} chars")

                if "No new facts" not in facts and len(facts.strip()) > 10:
                    memory, mrg_metrics = merge_facts(memory, facts, date, compact_model_id)
                    all_metrics.append(mrg_metrics)
                    log(f"    merge   {i+1}/{len(sessions)}: {mrg_metrics['latency_s']:.1f}s, mem={mrg_metrics['memory_size_chars']} chars")
            else:
                # Single-stage: raw compaction
                memory, metrics = compact_memory(
                    memory, pending_transcript, date, compact_model_id
                )
                all_metrics.append(metrics)
                log(f"    compact {i+1}/{len(sessions)}: {metrics['latency_s']:.1f}s, mem={metrics['memory_size_chars']} chars")
            pending_transcript = ""

    # Answer question using compacted memory
    answer, qa_metrics = answer_question(
        memory, question, question_date, reader_model_id
    )
    all_metrics.append(qa_metrics)

    return answer, memory, all_metrics


def load_config(config_path):
    """Load and validate a config file."""
    with open(config_path) as f:
        config = json.load(f)

    required = ["condition", "reader_model"]
    for key in required:
        if key not in config:
            raise ValueError(f"Config missing required key: {key}")

    if config["condition"] not in ("no-memory", "full-context", "soul", "soul-extract"):
        raise ValueError(f"Unknown condition: {config['condition']}")

    if config["condition"] in ("soul", "soul-extract"):
        if "compact_model" not in config:
            raise ValueError("Soul condition requires 'compact_model'")

    return config


def main():
    parser = argparse.ArgumentParser(description="SOUL-LongMemEval Adapter")
    parser.add_argument("--config", required=True, help="Path to config JSON")
    parser.add_argument("--data", required=True, help="Path to longmemeval_s_cleaned.json")
    parser.add_argument("--output-dir", default="results", help="Output directory")
    parser.add_argument("--limit", type=int, default=0, help="Limit number of instances (0=all)")
    parser.add_argument("--offset", type=int, default=0, help="Skip first N instances")
    parser.add_argument("--resume", action="store_true", help="Resume from last completed instance")
    args = parser.parse_args()

    config = load_config(args.config)
    config_name = Path(args.config).stem

    # Load dataset
    log(f"Loading dataset from {args.data}...")
    with open(args.data) as f:
        dataset = json.load(f)
    log(f"Loaded {len(dataset)} instances")

    # Apply offset/limit
    if args.offset:
        dataset = dataset[args.offset:]
    if args.limit:
        dataset = dataset[: args.limit]

    # Output paths
    output_dir = Path(args.output_dir) / config_name
    output_dir.mkdir(parents=True, exist_ok=True)
    predictions_path = output_dir / "predictions.jsonl"
    metrics_path = output_dir / "metrics.jsonl"
    memories_path = output_dir / "memories.jsonl"

    # Resume support: find already-completed question IDs
    completed_ids = set()
    if args.resume and predictions_path.exists():
        with open(predictions_path) as f:
            for line in f:
                obj = json.loads(line)
                completed_ids.add(obj["question_id"])
        log(f"Resuming: {len(completed_ids)} instances already completed")

    # Open output files in append mode
    pred_f = open(predictions_path, "a")
    metrics_f = open(metrics_path, "a")
    memories_f = open(memories_path, "a")

    try:
        total = len(dataset)
        for idx, instance in enumerate(dataset):
            qid = instance["question_id"]

            if qid in completed_ids:
                continue

            log(
                f"[{idx + 1}/{total}] Processing {qid} "
                f"(type={instance.get('question_type', '?')}, "
                f"sessions={len(instance.get('haystack_sessions', []))})"
            )

            try:
                answer, memory, metrics_list = process_instance(instance, config)
            except Exception as e:
                print(f"  ERROR: {e}", file=sys.stderr, flush=True)
                # Write error entry so we can skip on resume
                error_entry = {
                    "question_id": qid,
                    "hypothesis": "I don't know.",
                    "error": str(e),
                }
                pred_f.write(json.dumps(error_entry) + "\n")
                pred_f.flush()
                continue

            # Write prediction (LongMemEval format)
            prediction = {"question_id": qid, "hypothesis": answer}
            pred_f.write(json.dumps(prediction) + "\n")
            pred_f.flush()

            # Write per-instance metrics
            total_latency = sum(m.get("latency_s", 0) for m in metrics_list)
            total_input = sum(m.get("input_tokens", 0) for m in metrics_list)
            total_output = sum(m.get("output_tokens", 0) for m in metrics_list)
            compaction_count = sum(1 for m in metrics_list if m["type"] == "compaction")

            instance_metrics = {
                "question_id": qid,
                "question_type": instance.get("question_type", "unknown"),
                "condition": config_name,
                "num_sessions": len(instance.get("haystack_sessions", [])),
                "num_compactions": compaction_count,
                "total_input_tokens": total_input,
                "total_output_tokens": total_output,
                "total_latency_s": round(total_latency, 3),
                "final_memory_size_chars": len(memory) if memory else 0,
                "steps": metrics_list,
            }
            metrics_f.write(json.dumps(instance_metrics) + "\n")
            metrics_f.flush()

            # Write final memory state
            memory_entry = {"question_id": qid, "memory": memory}
            memories_f.write(json.dumps(memory_entry) + "\n")
            memories_f.flush()

            log(
                f"  Done: latency={total_latency:.1f}s, "
                f"compactions={compaction_count}, memory={len(memory) if memory else 0} chars"
            )

    finally:
        pred_f.close()
        metrics_f.close()
        memories_f.close()

    log(f"\nResults written to {output_dir}/")
    log(f"  predictions.jsonl — for LongMemEval evaluation")
    log(f"  metrics.jsonl     — cost/latency per instance")
    log(f"  memories.jsonl    — final memory states")


if __name__ == "__main__":
    main()
