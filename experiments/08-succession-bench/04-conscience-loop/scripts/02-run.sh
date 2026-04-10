#!/usr/bin/env bash
# Run a single conscience-loop experiment cell.
#
# Usage:
#   02-run.sh <condition> <run_id> [options]
#
# Options:
#   --instance <id>           instance_id from data/instances.json (default: first)
#   --driver <claude|opencode>  default: claude
#   --succession-config <file>  path to Succession config JSON to install as
#                               ~/.succession/config.json for this run
#   --prompt <control|treatment>  which prompt template (default: control)
#   --launch-replsh             always launch replsh before running (off by default)
#
# The condition is a label used for the run dir only — actual behavior is
# determined by --prompt and --succession-config.
set -euo pipefail

CONDITION="${1:?Usage: $0 <condition> <run_id> [options]}"
RUN_ID="${2:?Usage: $0 <condition> <run_id> [options]}"
shift 2

# Defaults
DRIVER="claude"
PROMPT_NAME="control"
INSTANCE_ID=""
SUCCESSION_CFG=""
LAUNCH_REPLSH="false"

while (( "$#" )); do
  case "$1" in
    --instance)          INSTANCE_ID="$2"; shift 2 ;;
    --driver)            DRIVER="$2"; shift 2 ;;
    --succession-config) SUCCESSION_CFG="$2"; shift 2 ;;
    --prompt)            PROMPT_NAME="$2"; shift 2 ;;
    --launch-replsh)     LAUNCH_REPLSH="true"; shift ;;
    *) echo "Unknown flag: $1" >&2; exit 2 ;;
  esac
done

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
EXP_DIR="$(dirname "$SCRIPT_DIR")"
DATA_DIR="$EXP_DIR/data"
PROMPTS_DIR="$EXP_DIR/prompts"
CONFIGS_DIR="$EXP_DIR/configs"
REPO_DIR="$EXP_DIR/repos/pytest"
RUN_DIR="$EXP_DIR/runs/$CONDITION/run_$RUN_ID"
INSTANCES_FILE="$DATA_DIR/instances.json"
TIMEOUT_SECS=7200
ALE_REPO_ROOT="$(cd "$EXP_DIR/../../.." && pwd)"

if [[ ! -d "$REPO_DIR/.git" ]]; then
  echo "ERROR: $REPO_DIR not cloned. Run 01-setup.sh first." >&2
  exit 1
fi

mkdir -p "$RUN_DIR"
echo "=== $CONDITION / run_$RUN_ID (driver=$DRIVER prompt=$PROMPT_NAME) ==="

# ── Install Succession config for this run (if provided) ──────────────
SUCCESSION_BACKUP=""
if [[ -n "$SUCCESSION_CFG" ]]; then
  if [[ ! -f "$SUCCESSION_CFG" ]]; then
    # Treat as bare name relative to configs/
    if [[ -f "$CONFIGS_DIR/$SUCCESSION_CFG" ]]; then
      SUCCESSION_CFG="$CONFIGS_DIR/$SUCCESSION_CFG"
    elif [[ -f "$CONFIGS_DIR/$SUCCESSION_CFG.json" ]]; then
      SUCCESSION_CFG="$CONFIGS_DIR/$SUCCESSION_CFG.json"
    else
      echo "ERROR: Succession config not found: $SUCCESSION_CFG" >&2
      exit 1
    fi
  fi
  mkdir -p "$HOME/.succession"
  if [[ -f "$HOME/.succession/config.json" ]]; then
    SUCCESSION_BACKUP="$HOME/.succession/config.json.bak.$$"
    cp "$HOME/.succession/config.json" "$SUCCESSION_BACKUP"
  fi
  cp "$SUCCESSION_CFG" "$HOME/.succession/config.json"
  cp "$SUCCESSION_CFG" "$RUN_DIR/succession-config.json"
  echo "  Succession config: $SUCCESSION_CFG"
fi

restore_succession_cfg() {
  if [[ -n "$SUCCESSION_BACKUP" && -f "$SUCCESSION_BACKUP" ]]; then
    mv "$SUCCESSION_BACKUP" "$HOME/.succession/config.json"
  elif [[ -n "$SUCCESSION_CFG" ]]; then
    rm -f "$HOME/.succession/config.json"
  fi
}
trap restore_succession_cfg EXIT

# ── Wire Succession hooks via repo-local .claude/settings.local.json ──
# Repo-local hook wiring (Option B): scopes all Succession hook commands
# to the pytest repo for this run. Does NOT touch ~/.claude/settings.json,
# so the surrounding Claude Code session is unaffected. Hooks reference
# the in-tree bb/src (not ~/.succession/bb/) so we test the code under
# version control, not a stale copy.
#
# Only wire hooks when a Succession config was provided. C-control and
# C-treatment (no --succession-config) must run as pure baselines — no
# SessionStart additionalContext, no judge, no reinject. Any prior
# settings.local.json from a previous B-* run in the same repo is
# explicitly removed to avoid cross-cell contamination.
rm -f "$REPO_DIR/.claude/settings.local.json"
if [[ -n "$SUCCESSION_CFG" ]]; then
mkdir -p "$REPO_DIR/.claude"
python3.8 - "$REPO_DIR/.claude/settings.local.json" "$ALE_REPO_ROOT/bb/src" <<'PYEOF'
import json, sys
out_path, bb_src = sys.argv[1:3]
def cmd(hook): return f"cat | bb -cp {bb_src} -m succession.hooks.{hook}"
settings = {
  "hooks": {
    "SessionStart": [
      {"hooks": [{"type": "command",
                   "command": f"bb -cp {bb_src} -m succession.hooks.session-start"}]}
    ],
    "PreToolUse": [
      {"matcher": "*",
       "hooks": [{"type": "command", "command": cmd("pre-tool-use")}]}
    ],
    "PostToolUse": [
      {"matcher": "*",
       "hooks": [{"type": "command", "command": cmd("post-tool-use")}]}
    ],
    "UserPromptSubmit": [
      {"hooks": [{"type": "command", "command": cmd("user-prompt-submit")}]}
    ],
    "Stop": [
      {"hooks": [{"type": "command", "command": cmd("stop")}]}
    ]
  }
}
with open(out_path, "w") as f:
  json.dump(settings, f, indent=2)
print(f"  Hooks wired: {out_path}")
PYEOF
else
  echo "  Hooks: none (no --succession-config → pure baseline cell)"
fi

# ── Reset repo to clean state (preserve .venv and .succession) ────────
cd "$REPO_DIR"
git reset --hard HEAD --quiet 2>/dev/null || true
git clean -fd --exclude=.venv --exclude=.succession --exclude=.claude --exclude=.replsh --exclude=skills --quiet 2>/dev/null || true

# Wipe prior-run judge log + compiled rules so this run's succession snapshot
# is scoped only to this run. The rules/ dir is re-seeded below from ALE_REPO_ROOT.
rm -rf "$REPO_DIR/.succession/log" "$REPO_DIR/.succession/compiled"

# Wipe stale per-session state files in /tmp. Claude allocates a fresh
# session UUID each invocation so collisions are unlikely, but old state
# from crashed prior runs would otherwise accumulate and the reinject
# emission cap key could survive across cells if the same UUID reappears.
rm -f /tmp/.succession-reinject-state-* /tmp/.succession-turns-* \
      /tmp/.succession-tool-count-* /tmp/.succession-judge-budget-* \
      /tmp/.succession-correction-flag-* /tmp/.succession-extract-offset-* \
      2>/dev/null || true

# ── Copy source to site-packages (fix for non-editable install) ───────
cp -r src/_pytest .venv/lib/python3.8/site-packages/ 2>/dev/null || true

# ── Seed the repo with Succession rules + replsh skill ─────────────────
# SessionStart will pick these up and copy the skill into .claude/skills/.
mkdir -p "$REPO_DIR/.succession/rules" "$REPO_DIR/skills/soul/replsh"
cp "$ALE_REPO_ROOT/.succession/rules/verify-via-repl.md" "$REPO_DIR/.succession/rules/"
cp "$ALE_REPO_ROOT/.succession/rules/judge-conscience-framing.md" "$REPO_DIR/.succession/rules/"
cp "$ALE_REPO_ROOT/skills/soul/replsh/SKILL.md" "$REPO_DIR/skills/soul/replsh/SKILL.md"

# ── Build prompt ────────────────────────────────────────────────────
python3.8 - "$INSTANCES_FILE" "$PROMPTS_DIR/$PROMPT_NAME.txt" "$RUN_DIR" "$INSTANCE_ID" <<'PYEOF'
import json, sys
instances_file, template_file, run_dir, instance_id = sys.argv[1:5]
data = json.load(open(instances_file))
if instance_id:
    inst = next((d for d in data if d["instance_id"] == instance_id), None)
    if inst is None:
        print(f"ERROR: instance {instance_id} not in {instances_file}", file=sys.stderr)
        sys.exit(1)
else:
    inst = data[0]
template = open(template_file).read()
prompt = (template
    .replace("{repo}", inst["repo"])
    .replace("{base_commit}", inst["base_commit"])
    .replace("{problem_statement}", inst["problem_statement"]))
with open(f"{run_dir}/prompt.txt", "w") as f:
    f.write(prompt)
with open(f"{run_dir}/instance.json", "w") as f:
    json.dump({"instance_id": inst["instance_id"]}, f)
print(f"  Prompt: {len(prompt)} chars ({inst['instance_id']})")
PYEOF

# ── Launch replsh (treatment prompt or --launch-replsh) ───────────────
NEED_REPLSH="false"
if [[ "$PROMPT_NAME" == "treatment" || "$LAUNCH_REPLSH" == "true" ]]; then
  NEED_REPLSH="true"
fi
if [[ "$NEED_REPLSH" == "true" ]]; then
  replsh stop swebench 2>/dev/null || true
  mkdir -p "$REPO_DIR/.replsh"
  cat > "$REPO_DIR/.replsh/config.edn" <<EDN
{:sessions
 {"swebench" {:toolchain "python.venv"
              :cwd       "./"}}}
EDN
  replsh launch --name swebench 2>&1 || true
  echo "  replsh session launched"
fi

# ── Run the driver with timeout ────────────────────────────────────────
T_START=$(python3.8 -c "import time; print(int(time.time()*1000))")

run_claude() {
  # bypassPermissions required: the user's global settings.json has
  # defaultMode=plan, which is inherited by claude -p and blocks
  # ExitPlanMode in headless mode. In a sandboxed per-run pytest clone
  # with clean git state each run, bypassing permission prompts is safe.
  cd "$REPO_DIR" && cat "$RUN_DIR/prompt.txt" | claude -p \
    --output-format json \
    --model claude-sonnet-4-6 \
    --permission-mode bypassPermissions \
    > "$RUN_DIR/driver.json" 2>"$RUN_DIR/driver.stderr"
}

run_opencode() {
  export OPENCODE_PERMISSION='{"*":"allow"}'
  export OPENCODE_EXPERIMENTAL_BASH_DEFAULT_TIMEOUT_MS="120000"
  cd "$REPO_DIR" && cat "$RUN_DIR/prompt.txt" | opencode run \
    --model "openrouter/anthropic/claude-sonnet-4.6" \
    --format json \
    > "$RUN_DIR/driver.json" 2>"$RUN_DIR/driver.stderr"
}

TIMEOUT_OCCURRED=false
(
  case "$DRIVER" in
    claude)   run_claude ;;
    opencode) run_opencode ;;
    *) echo "unknown driver: $DRIVER" >&2; exit 1 ;;
  esac
) &
CHILD_PID=$!

SECONDS_ELAPSED=0
while kill -0 "$CHILD_PID" 2>/dev/null; do
  if [[ $SECONDS_ELAPSED -ge $TIMEOUT_SECS ]]; then
    echo "  TIMEOUT after ${TIMEOUT_SECS}s — killing driver"
    kill "$CHILD_PID" 2>/dev/null || true
    sleep 2
    kill -9 "$CHILD_PID" 2>/dev/null || true
    TIMEOUT_OCCURRED=true
    break
  fi
  sleep 10
  SECONDS_ELAPSED=$((SECONDS_ELAPSED + 10))
done

if [[ "$TIMEOUT_OCCURRED" == "false" ]]; then
  wait "$CHILD_PID" 2>/dev/null || true
fi

T_END=$(python3.8 -c "import time; print(int(time.time()*1000))")
echo "{\"start_ms\": $T_START, \"end_ms\": $T_END, \"timeout\": $TIMEOUT_OCCURRED, \"driver\": \"$DRIVER\"}" > "$RUN_DIR/timing.json"

# ── Extract patch ───────────────────────────────────────────────────
cd "$REPO_DIR"
git diff -- ':!.succession' ':!.claude' ':!.replsh' ':!skills' > "$RUN_DIR/patch.diff" 2>/dev/null || true
PATCH_LINES=$(wc -l < "$RUN_DIR/patch.diff" | tr -d ' ')
echo "  Patch: $PATCH_LINES lines"
echo "  Timeout: $TIMEOUT_OCCURRED"

# ── Save meta ───────────────────────────────────────────────────────
python3.8 - "$RUN_DIR" "$TIMEOUT_OCCURRED" "$DRIVER" "$PROMPT_NAME" "$CONDITION" <<'PYEOF'
import json, sys
run_dir, timeout, driver, prompt_name, condition = sys.argv[1:6]
meta = {"timed_out": timeout == "true",
        "driver": driver,
        "prompt": prompt_name,
        "condition": condition}
json.dump(meta, open(f"{run_dir}/meta.json", "w"), indent=2)
PYEOF

# ── Snapshot Succession artifacts for analysis ────────────────────────
if [[ -d "$REPO_DIR/.succession" ]]; then
  mkdir -p "$RUN_DIR/succession"
  cp -r "$REPO_DIR/.succession/log"      "$RUN_DIR/succession/log"      2>/dev/null || true
  cp -r "$REPO_DIR/.succession/compiled" "$RUN_DIR/succession/compiled" 2>/dev/null || true
fi

# ── Save replsh eval log if applicable ────────────────────────────────
if [[ "$NEED_REPLSH" == "true" ]]; then
  replsh stop swebench 2>/dev/null || true
fi

echo "  Done → $RUN_DIR/"
