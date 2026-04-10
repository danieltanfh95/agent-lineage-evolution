#!/usr/bin/env bash
# Aggregate runs under runs/<cond>/run_*/ into results/summary.json.
# Extends v4 analyzer with judge verdict distribution + reinject-fires + cost.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
EXP_DIR="$(dirname "$SCRIPT_DIR")"
RESULTS_DIR="$EXP_DIR/results"
RUNS_DIR="$EXP_DIR/runs"

mkdir -p "$RESULTS_DIR"

python3.8 - "$RUNS_DIR" "$RESULTS_DIR" <<'PYEOF'
import json, os, glob, statistics, sys, collections

runs_dir, results_dir = sys.argv[1:3]

def iter_events(path):
    if not os.path.exists(path):
        return
    with open(path) as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                yield json.loads(line)
            except Exception:
                pass

def parse_driver_output(path, driver):
    """Yield normalized {type, tool, input, tokens, ts} events across drivers."""
    if not os.path.exists(path):
        return
    if driver == "opencode":
        # JSONL stream from opencode run --format json
        for ev in iter_events(path):
            yield ev
    elif driver == "claude":
        # claude -p --output-format json emits a single JSON doc or array.
        try:
            with open(path) as f:
                raw = json.load(f)
        except Exception:
            return
        items = raw if isinstance(raw, list) else [raw]
        for item in items:
            # Best-effort token counts from the final result envelope.
            usage = item.get("usage") or item.get("total_usage") or {}
            if usage:
                yield {"type": "usage", "usage": usage}
            # Tool calls may appear nested under messages
            for msg in item.get("messages", []) or []:
                content = msg.get("content")
                if isinstance(content, list):
                    for part in content:
                        if isinstance(part, dict) and part.get("type") == "tool_use":
                            yield {"type": "tool_use",
                                   "tool": part.get("name", "?"),
                                   "input": part.get("input", {})}

def analyze_run(run_dir):
    m = {
        "condition": "",
        "run_id": "",
        "instance_id": "",
        "driver": "",
        "prompt": "",
        "resolved": False,
        "timed_out": False,
        "patch_lines": 0,
        "wall_time_s": 0.0,
        "llm_time_s": 0.0,
        "tool_time_s": 0.0,
        "total_tokens": 0,
        "input_tokens": 0,
        "output_tokens": 0,
        "tool_calls": 0,
        "tool_breakdown": {},
        "repl_evals": 0,
        # Conscience-loop extras
        "judge_verdicts": {"followed": 0, "violated": 0, "ambiguous": 0, "not-applicable": 0},
        "judge_calls": 0,
        "judge_cost_usd": 0.0,
        "reinject_fires": 0,
    }

    # patch.diff
    pf = os.path.join(run_dir, "patch.diff")
    if os.path.exists(pf):
        m["patch_lines"] = sum(1 for _ in open(pf))

    # result.json
    rf = os.path.join(run_dir, "result.json")
    if os.path.exists(rf):
        r = json.load(open(rf))
        m["resolved"] = bool(r.get("resolved", False))

    # meta.json
    mf = os.path.join(run_dir, "meta.json")
    if os.path.exists(mf):
        meta = json.load(open(mf))
        m["timed_out"] = bool(meta.get("timed_out", False))
        m["driver"] = meta.get("driver", "")
        m["prompt"] = meta.get("prompt", "")

    # instance.json
    inf = os.path.join(run_dir, "instance.json")
    if os.path.exists(inf):
        m["instance_id"] = json.load(open(inf)).get("instance_id", "")

    # timing.json
    tf = os.path.join(run_dir, "timing.json")
    if os.path.exists(tf):
        t = json.load(open(tf))
        m["wall_time_s"] = (t["end_ms"] - t["start_ms"]) / 1000.0
        m["timed_out"] = m["timed_out"] or t.get("timeout", False)
        if not m["driver"]:
            m["driver"] = t.get("driver", "")

    # driver output (either driver.json or v4-legacy opencode.json)
    driver_file = os.path.join(run_dir, "driver.json")
    if not os.path.exists(driver_file):
        driver_file = os.path.join(run_dir, "opencode.json")
    if os.path.exists(driver_file):
        events = list(parse_driver_output(driver_file, m["driver"] or "opencode"))
        step_starts = {}
        llm_ms = 0
        for ev in events:
            et = ev.get("type", "")
            ts = ev.get("timestamp", 0)
            if et == "step_start":
                step_starts[ev.get("part", {}).get("messageID", "")] = ts
            elif et == "step_finish":
                part = ev.get("part", {})
                mid = part.get("messageID", "")
                if mid in step_starts:
                    llm_ms += ts - step_starts[mid]
                tok = part.get("tokens", {})
                m["total_tokens"] += tok.get("total", 0)
                m["input_tokens"] += tok.get("input", 0)
                m["output_tokens"] += tok.get("output", 0)
            elif et == "tool_use":
                m["tool_calls"] += 1
                part = ev.get("part", {}) if "part" in ev else ev
                tool = part.get("tool") or ev.get("tool") or "?"
                m["tool_breakdown"][tool] = m["tool_breakdown"].get(tool, 0) + 1
                state = part.get("state", {}) if "state" in part else {}
                tr = state.get("time", {})
                if tr.get("start") and tr.get("end"):
                    m["tool_time_s"] += (tr["end"] - tr["start"]) / 1000.0
                inp = state.get("input") or ev.get("input") or {}
                cmd = inp.get("command", "") if isinstance(inp, dict) else ""
                if tool == "bash" and "replsh eval" in cmd:
                    m["repl_evals"] += 1
                elif tool in ("Bash", "bash") and "replsh eval" in cmd:
                    m["repl_evals"] += 1
                elif tool == "replsh":
                    m["repl_evals"] += 1
            elif et == "usage":
                u = ev.get("usage", {})
                m["total_tokens"] += u.get("total_tokens", u.get("total", 0))
                m["input_tokens"] += u.get("input_tokens", u.get("input", 0))
                m["output_tokens"] += u.get("output_tokens", u.get("output", 0))
        m["llm_time_s"] = llm_ms / 1000.0

    # Judge log (conscience cells only)
    judge_log = os.path.join(run_dir, "succession", "log", "judge.jsonl")
    for ev in iter_events(judge_log):
        if ev.get("kind") == "tool" or ev.get("kind") == "turn":
            m["judge_calls"] += 1
            v = ev.get("verdict", "")
            if isinstance(v, str) and v in m["judge_verdicts"]:
                m["judge_verdicts"][v] += 1
            m["judge_cost_usd"] += float(ev.get("cost_usd", 0.0) or 0.0)

    # reinject_fires — approximate: count additionalContext events from
    # the driver transcript tagged with "SUCCESSION: ACTIVE RULES DIGEST"
    # (PostToolUse + Stop reinject).
    if os.path.exists(driver_file):
        try:
            with open(driver_file) as f:
                blob = f.read()
            m["reinject_fires"] = blob.count("SUCCESSION: ACTIVE RULES DIGEST")
        except Exception:
            pass

    return m

all_runs = []
for cond_dir in sorted(glob.glob(f"{runs_dir}/*")):
    if not os.path.isdir(cond_dir):
        continue
    cond = os.path.basename(cond_dir)
    for rd in sorted(glob.glob(f"{cond_dir}/run_*")):
        if not os.path.isdir(rd):
            continue
        m = analyze_run(rd)
        m["condition"] = cond
        m["run_id"] = os.path.basename(rd)
        all_runs.append(m)

def agg(runs):
    if not runs:
        return {}
    n = len(runs)
    res = sum(1 for r in runs if r["resolved"])
    timeouts = sum(1 for r in runs if r["timed_out"])
    def s(k):
        v = [r[k] for r in runs]
        return {"mean": round(statistics.mean(v), 1),
                "std": round(statistics.stdev(v), 1) if n > 1 else 0}
    total_verdicts = collections.Counter()
    for r in runs:
        total_verdicts.update(r.get("judge_verdicts", {}))
    return {"n": n,
            "resolved": res,
            "resolution_rate": round(res / n, 2),
            "timed_out": timeouts,
            "wall_time_s": s("wall_time_s"),
            "llm_time_s": s("llm_time_s"),
            "total_tokens": s("total_tokens"),
            "input_tokens": s("input_tokens"),
            "output_tokens": s("output_tokens"),
            "tool_calls": s("tool_calls"),
            "repl_evals": s("repl_evals"),
            "patch_lines": s("patch_lines"),
            "judge_calls": s("judge_calls"),
            "judge_cost_usd": {"mean": round(statistics.mean([r["judge_cost_usd"] for r in runs]), 4)},
            "reinject_fires": s("reinject_fires"),
            "judge_verdict_totals": dict(total_verdicts)}

summary = {"runs": all_runs, "comparison": {}}
conds = sorted({r["condition"] for r in all_runs})
for cond in conds:
    runs = [r for r in all_runs if r["condition"] == cond]
    summary["comparison"][cond] = agg(runs)

json.dump(summary, open(f"{results_dir}/summary.json", "w"), indent=2)

# ── Pretty print ────────────────────────────────────────────────────
print()
print("=" * 95)
print("  Experiment 08/04 — Conscience loop (SWE-bench)")
print("=" * 95)
print()
header = f"  {'Condition':<22}{'n':>4}{'resolved':>11}{'repl_evals':>13}{'tok_tot':>12}{'judge$':>10}{'reinject':>11}"
print(header)
print("  " + "─" * (len(header) - 2))
for cond, c in summary["comparison"].items():
    resolved_s = f"{c['resolved']}/{c['n']}"
    repl_s = c["repl_evals"]["mean"]
    tok_s = c["total_tokens"]["mean"]
    judge_s = c.get("judge_cost_usd", {}).get("mean", 0.0)
    rein_s = c["reinject_fires"]["mean"]
    print(f"  {cond:<22}{c['n']:>4}{resolved_s:>11}{repl_s:>13.1f}{tok_s:>12.0f}{judge_s:>10.4f}{rein_s:>11.1f}")

print(f"\n  Full data: {results_dir}/summary.json\n")
PYEOF
