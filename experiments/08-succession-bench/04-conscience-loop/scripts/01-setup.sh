#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
V4_DIR="$(dirname "$SCRIPT_DIR")"
REPOS_DIR="$V4_DIR/repos"
DATA_DIR="$V4_DIR/data"

mkdir -p "$REPOS_DIR" "$DATA_DIR"

# ── Ensure every instance in instances.json has its repo cloned ──────
# Multi-instance aware. Each entry in instances.json must have a
# `repo_dir` field; the clone lands at $REPOS_DIR/<repo_dir>. Legacy
# pytest-7373 maps to $REPOS_DIR/pytest to match the existing shared
# clone used by the prior B-*/C-* sweeps.
#
# If a new instance is listed in instances.json without metadata fields
# (e.g. only instance_id + repo_dir), this script will fetch the full
# row from SWE-bench Lite and back-fill it. Runs idempotently.
python3.8 - "$DATA_DIR" "$REPOS_DIR" <<'PYEOF'
import json, sys, subprocess, os, shutil, glob as g

data_dir, repos_dir = sys.argv[1:3]
inst_file = f"{data_dir}/instances.json"

if not os.path.exists(inst_file):
    print(f"ERROR: {inst_file} missing. Seed with at least one instance_id first.")
    sys.exit(1)

with open(inst_file) as f:
    instances = json.load(f)

# Back-fill missing metadata from SWE-bench Lite (only if needed)
needs_fetch = [e for e in instances
               if not all(k in e for k in ("base_commit", "problem_statement"))]
if needs_fetch:
    print(f"Fetching {len(needs_fetch)} incomplete instances from SWE-bench Lite...")
    from datasets import load_dataset
    ds = load_dataset("princeton-nlp/SWE-bench_Lite", split="test")
    by_id = {r["instance_id"]: r for r in ds}
    for inst in needs_fetch:
        iid = inst["instance_id"]
        if iid not in by_id:
            print(f"ERROR: {iid} not in SWE-bench Lite"); sys.exit(1)
        row = by_id[iid]
        inst.update({
            "repo": row["repo"],
            "base_commit": row["base_commit"],
            "problem_statement": row["problem_statement"],
            "FAIL_TO_PASS": row["FAIL_TO_PASS"],
            "PASS_TO_PASS": row["PASS_TO_PASS"],
            "patch": row["patch"],
            "test_patch": row.get("test_patch", ""),
        })
    with open(inst_file, "w") as f:
        json.dump(instances, f, indent=2)

# Ensure each instance has repo_dir, default to "pytest" for legacy 7373
for inst in instances:
    if "repo_dir" not in inst:
        inst["repo_dir"] = ("pytest" if inst["instance_id"] == "pytest-dev__pytest-7373"
                            else inst["instance_id"].replace("pytest-dev__", ""))

with open(inst_file, "w") as f:
    json.dump(instances, f, indent=2)

# Clone + venv setup per instance
for inst in instances:
    iid = inst["instance_id"]
    repo_dir = f"{repos_dir}/{inst['repo_dir']}"
    if os.path.isdir(f"{repo_dir}/.git"):
        print(f"  {iid}: clone exists at {repo_dir}, skipping")
        continue

    print(f"  {iid}: cloning {inst['repo']} → {repo_dir}...")
    subprocess.run(
        ["git", "clone", f"https://github.com/{inst['repo']}.git", repo_dir, "--quiet"],
        check=True
    )
    subprocess.run(
        ["git", "checkout", inst["base_commit"], "--quiet"],
        cwd=repo_dir, check=True
    )

    print(f"  {iid}: creating Python 3.8 venv + installing deps...")
    subprocess.run(["python3.8", "-m", "venv", f"{repo_dir}/.venv"], check=True)
    venv_pip = f"{repo_dir}/.venv/bin/pip"

    subprocess.run([venv_pip, "install", "-e", f"{repo_dir}[testing]", "--quiet"],
                   capture_output=True)

    # pip install -e may not produce a proper editable install on older pytest
    # commits. Copy source _pytest to site-packages so REPL and venv python
    # see source code.
    src_pytest = os.path.join(repo_dir, "src", "_pytest")
    for site_pkgs in g.glob(f"{repo_dir}/.venv/lib/python*/site-packages"):
        dst = os.path.join(site_pkgs, "_pytest")
        if os.path.isdir(src_pytest):
            if os.path.exists(dst):
                shutil.rmtree(dst)
            shutil.copytree(src_pytest, dst)
            print(f"  Copied source _pytest to {site_pkgs}")

    print(f"  {iid}: ready")

print("\nSetup complete.")
for inst in instances:
    print(f"  {inst['instance_id']:32s} → {repos_dir}/{inst['repo_dir']}")
PYEOF
