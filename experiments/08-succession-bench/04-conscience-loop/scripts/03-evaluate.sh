#!/usr/bin/env bash
# Evaluate a single run against its instance's FAIL_TO_PASS + PASS_TO_PASS
# test lists. Port of v4/03-evaluate.sh; accepts an arbitrary condition label
# (not just control/treatment) and reads run_dir/instance.json to find the
# instance id if the experiment is multi-instance.
set -euo pipefail

CONDITION="${1:?Usage: $0 <condition> <run_id>}"
RUN_ID="${2:?}"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
EXP_DIR="$(dirname "$SCRIPT_DIR")"
DATA_DIR="$EXP_DIR/data"
REPO_DIR="$EXP_DIR/repos/pytest"
RUN_DIR="$EXP_DIR/runs/$CONDITION/run_$RUN_ID"
INSTANCES_FILE="$DATA_DIR/instances.json"
V4_DIR="$EXP_DIR" # kept for heredoc compat below

echo "=== Eval: $CONDITION / $RUN_ID ==="

if [[ ! -s "$RUN_DIR/patch.diff" ]]; then
  echo "  No patch — marking unresolved"
  python3.8 -c "import json; json.dump({'condition':'$CONDITION','run_id':'$RUN_ID','resolved':False,'error':'no patch'}, open('$RUN_DIR/result.json','w'), indent=2)"
  exit 0
fi

# ── Reset repo, apply test_patch + model patch ──────────────────────
cd "$REPO_DIR"
git reset --hard HEAD --quiet 2>/dev/null || true
git clean -fd --quiet 2>/dev/null || true

# Apply test_patch (so FAIL_TO_PASS tests exist)
python3.8 - "$INSTANCES_FILE" "$REPO_DIR" "$RUN_DIR" <<'PYEOF'
import json, sys, subprocess, os
instances_file, repo_dir, run_dir = sys.argv[1:4]
data = json.load(open(instances_file))
# Pick instance by the recorded id if present, else the first
inst_meta_file = os.path.join(run_dir, "instance.json")
target_id = None
if os.path.exists(inst_meta_file):
    target_id = json.load(open(inst_meta_file)).get("instance_id")
if target_id:
    inst = next((d for d in data if d["instance_id"] == target_id), data[0])
else:
    inst = data[0]
tp = inst.get("test_patch", "")
if tp:
    tp_file = f"{repo_dir}/_test_patch.diff"
    with open(tp_file, "w") as f:
        f.write(tp)
    result = subprocess.run(["git", "apply", "_test_patch.diff"], cwd=repo_dir, capture_output=True, text=True)
    if result.returncode != 0:
        print(f"  WARNING: test_patch apply failed: {result.stderr[:200]}")
PYEOF

# Apply model patch
cd "$REPO_DIR"
APPLY_RESULT=0
git apply "$RUN_DIR/patch.diff" 2>"$RUN_DIR/apply.stderr" || APPLY_RESULT=$?
if [[ $APPLY_RESULT -ne 0 ]]; then
  echo "  Patch apply failed — marking unresolved"
  python3.8 -c "import json; json.dump({'condition':'$CONDITION','run_id':'$RUN_ID','resolved':False,'error':'patch apply failed'}, open('$RUN_DIR/result.json','w'), indent=2)"
  rm -f "$REPO_DIR/_test_patch.diff"
  exit 0
fi

# Copy source to site-packages for test evaluation
cp -r src/_pytest .venv/lib/python3.8/site-packages/ 2>/dev/null || true

# ── Run tests ───────────────────────────────────────────────────────
python3.8 - "$INSTANCES_FILE" "$RUN_DIR" "$REPO_DIR" "$CONDITION" <<'PYEOF'
import json, sys, subprocess, re, os

instances_file, run_dir, repo_dir, condition = sys.argv[1:5]
data = json.load(open(instances_file))
inst_meta_file = os.path.join(run_dir, "instance.json")
target_id = None
if os.path.exists(inst_meta_file):
    target_id = json.load(open(inst_meta_file)).get("instance_id")
if target_id:
    inst = next((d for d in data if d["instance_id"] == target_id), data[0])
else:
    inst = data[0]

ftp_tests = json.loads(inst["FAIL_TO_PASS"]) if isinstance(inst["FAIL_TO_PASS"], str) else inst["FAIL_TO_PASS"]
ptp_tests = json.loads(inst["PASS_TO_PASS"]) if isinstance(inst["PASS_TO_PASS"], str) else inst["PASS_TO_PASS"]

venv_python = os.path.join(repo_dir, ".venv", "bin", "python")
if not os.path.exists(venv_python):
    venv_python = "python3.8"

# Collect test files from FTP and PTP test node IDs
all_tests = set(ftp_tests + ptp_tests)
test_files = set()
for t in all_tests:
    if "::" in t:
        test_files.add(t.split("::")[0])

# Run tests with -v for results
# Parse lines like: path::Test::func[param] PASSED [ 42%]
# Using string splitting instead of regex to handle special chars in test names
test_results = {}
total_passed = 0
total_failed = 0

for tf in sorted(test_files):
    r = subprocess.run(
        [venv_python, "-m", "pytest", "-v", "--tb=short", tf],
        capture_output=True, text=True, timeout=600, cwd=repo_dir
    )
    for line in r.stdout.splitlines():
        for status in ("PASSED", "FAILED", "ERROR"):
            idx = line.find(" " + status)
            if idx > 0 and "::" in line[:idx]:
                test_results[line[:idx].strip()] = status
                break
    sm = re.search(r'(\d+) passed', r.stdout)
    if sm: total_passed += int(sm.group(1))
    sm = re.search(r'(\d+) failed', r.stdout)
    if sm: total_failed += int(sm.group(1))

def check_pass(test_str):
    if test_str in test_results:
        return test_results[test_str] == "PASSED"
    matches = [(nid, st) for nid, st in test_results.items() if nid.startswith(test_str)]
    if matches:
        return all(st == "PASSED" for _, st in matches)
    return False

print(f"  Running {len(ftp_tests)} FAIL_TO_PASS tests...")
ftp_results = []
for test in ftp_tests:
    passed = check_pass(test)
    ftp_results.append({"test": test, "passed": passed})
    print(f"    {'PASS' if passed else 'FAIL'} {test}")

print(f"  Running {len(ptp_tests)} PASS_TO_PASS tests...")
ptp_results = []
for test in ptp_tests:
    passed = check_pass(test)
    ptp_results.append({"test": test, "passed": passed})

ftp_passed = sum(1 for r in ftp_results if r["passed"])
ptp_passed = sum(1 for r in ptp_results if r["passed"])
resolved = all(r["passed"] for r in ftp_results) and all(r["passed"] for r in ptp_results)

result = {
    "condition": condition,
    "run_id": os.path.basename(run_dir),
    "resolved": resolved,
    "fail_to_pass": {"passed": ftp_passed, "total": len(ftp_results)},
    "pass_to_pass": {"passed": ptp_passed, "total": len(ptp_results)},
    "test_totals": {"passed": total_passed, "failed": total_failed},
}
json.dump(result, open(f"{run_dir}/result.json", "w"), indent=2)
print(f"  => resolved={resolved}  FTP={ftp_passed}/{len(ftp_results)}  PTP={ptp_passed}/{len(ptp_results)}  (suite: {total_passed} passed, {total_failed} failed)")
PYEOF

rm -f "$REPO_DIR/_test_patch.diff"
echo ""
