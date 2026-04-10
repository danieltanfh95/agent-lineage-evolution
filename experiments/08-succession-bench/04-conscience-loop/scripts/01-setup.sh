#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
V4_DIR="$(dirname "$SCRIPT_DIR")"
REPOS_DIR="$V4_DIR/repos"
DATA_DIR="$V4_DIR/data"

mkdir -p "$REPOS_DIR" "$DATA_DIR"

# ── Export instance metadata ─────────────────────────────────────────
python3.8 - "$DATA_DIR" <<'PYEOF'
import json, sys
from datasets import load_dataset

data_dir = sys.argv[1]
INSTANCE_ID = "pytest-dev__pytest-7373"

print("Loading SWE-bench Lite from Hugging Face...")
ds = load_dataset("princeton-nlp/SWE-bench_Lite", split="test")
by_id = {row["instance_id"]: row for row in ds}

if INSTANCE_ID not in by_id:
    print(f"ERROR: {INSTANCE_ID} not found in dataset")
    sys.exit(1)

row = by_id[INSTANCE_ID]
instance = {
    "instance_id": row["instance_id"],
    "repo": row["repo"],
    "base_commit": row["base_commit"],
    "problem_statement": row["problem_statement"],
    "FAIL_TO_PASS": row["FAIL_TO_PASS"],
    "PASS_TO_PASS": row["PASS_TO_PASS"],
    "patch": row["patch"],
    "test_patch": row.get("test_patch", ""),
}
print(f"  {INSTANCE_ID} ({row['repo']})")

with open(f"{data_dir}/instances.json", "w") as f:
    json.dump([instance], f, indent=2)
print(f"Exported instance metadata")
PYEOF

# ── Clone + set up pytest ───────────────────────────────────────────
python3.8 - "$DATA_DIR" "$REPOS_DIR" <<'PYEOF'
import json, sys, subprocess, os, shutil, glob as g

data_dir, repos_dir = sys.argv[1:3]
instances = json.load(open(f"{data_dir}/instances.json"))
inst = instances[0]
iid = inst["instance_id"]
repo_dir = f"{repos_dir}/pytest"

if os.path.isdir(f"{repo_dir}/.git"):
    print(f"  {iid}: already cloned, skipping")
    sys.exit(0)

print(f"  {iid}: cloning {inst['repo']}...")
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

# Install project with test extras
subprocess.run([venv_pip, "install", "-e", f"{repo_dir}[testing]", "--quiet"],
               capture_output=True)

# pip install -e on pytest 5.4.1 doesn't create a proper editable install.
# Copy source _pytest to site-packages so REPL and venv python see source code.
src_pytest = os.path.join(repo_dir, "src", "_pytest")
for site_pkgs in g.glob(f"{repo_dir}/.venv/lib/python*/site-packages"):
    dst = os.path.join(site_pkgs, "_pytest")
    if os.path.isdir(src_pytest):
        if os.path.exists(dst):
            shutil.rmtree(dst)
        shutil.copytree(src_pytest, dst)
        print(f"  Copied source _pytest to {site_pkgs}")

print(f"  {iid}: ready")
print("\nRepo set up.")
PYEOF

echo ""
echo "Setup complete. Shared repo in: $REPOS_DIR/pytest"
