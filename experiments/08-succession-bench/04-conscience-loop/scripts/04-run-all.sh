#!/usr/bin/env bash
# Run the conscience-loop experiment across an (instance × condition × rep)
# matrix. Conditions are defined inline below as tuples of
# (label prompt config driver extra_flags).
#
# Usage:
#   04-run-all.sh [--reps N] [--instances id1,id2,...] [--conditions name1,name2,...]
#                 [--sweep signal|main]
#
#   --sweep signal  → 1 instance × all conditions × reps (default 1)
#   --sweep main    → all instances × {C-control,C-treatment,B-base,B-all,B-all-mech-weak} × reps
#
# Condition rows (label | prompt | config | driver):
#   C-control        control   none              claude
#   C-treatment      treatment none              claude
#   B-base           control   B-base            claude
#   B-reinject       control   B-reinject        claude
#   B-repl           control   B-repl            claude
#   B-judge-async    control   B-judge-async     claude
#   B-all            control   B-all             claude
#   B-all-mech-weak  control   B-all-mech-weak   claude
#   B-all-opencode   control   B-all             opencode  (driver ablation)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
EXP_DIR="$(dirname "$SCRIPT_DIR")"
DATA_DIR="$EXP_DIR/data"

REPS=1
SWEEP="signal"
INSTANCES_OVERRIDE=""
CONDITIONS_OVERRIDE=""

while (( "$#" )); do
  case "$1" in
    --reps)       REPS="$2"; shift 2 ;;
    --sweep)      SWEEP="$2"; shift 2 ;;
    --instances)  INSTANCES_OVERRIDE="$2"; shift 2 ;;
    --conditions) CONDITIONS_OVERRIDE="$2"; shift 2 ;;
    *) echo "Unknown flag: $1" >&2; exit 2 ;;
  esac
done

if [[ ! -f "$DATA_DIR/instances.json" ]]; then
  echo "ERROR: Run 01-setup.sh first." >&2
  exit 1
fi

# All known condition rows (label|prompt|config|driver)
ALL_CONDITIONS=(
  "C-control|control|none|claude"
  "C-treatment|treatment|none|claude"
  "B-base|control|B-base|claude"
  "B-reinject|control|B-reinject|claude"
  "B-repl|control|B-repl|claude"
  "B-judge-async|control|B-judge-async|claude"
  "B-all|control|B-all|claude"
  "B-all-mech-weak|control|B-all-mech-weak|claude"
)

MAIN_SWEEP_LABELS="C-control C-treatment B-base B-all B-all-mech-weak"

# Pick conditions
if [[ -n "$CONDITIONS_OVERRIDE" ]]; then
  IFS=',' read -ra SELECTED <<<"$CONDITIONS_OVERRIDE"
elif [[ "$SWEEP" == "main" ]]; then
  SELECTED=($MAIN_SWEEP_LABELS)
else
  SELECTED=()
  for row in "${ALL_CONDITIONS[@]}"; do
    SELECTED+=("${row%%|*}")
  done
fi

# Pick instances
if [[ -n "$INSTANCES_OVERRIDE" ]]; then
  IFS=',' read -ra INSTANCES <<<"$INSTANCES_OVERRIDE"
elif [[ "$SWEEP" == "signal" ]]; then
  INSTANCES=("pytest-dev__pytest-7373")
else
  # Read every instance_id from instances.json
  mapfile -t INSTANCES < <(python3.8 -c 'import json,sys; [print(d["instance_id"]) for d in json.load(open(sys.argv[1]))]' "$DATA_DIR/instances.json")
fi

echo "Sweep: $SWEEP"
echo "Instances: ${INSTANCES[*]}"
echo "Conditions: ${SELECTED[*]}"
echo "Reps: $REPS"

# Lookup row by label
row_for() {
  local label="$1"
  for row in "${ALL_CONDITIONS[@]}"; do
    if [[ "${row%%|*}" == "$label" ]]; then
      echo "$row"
      return 0
    fi
  done
  return 1
}

TOTAL=0
for inst in "${INSTANCES[@]}"; do
  for label in "${SELECTED[@]}"; do
    row=$(row_for "$label") || { echo "Unknown condition label: $label" >&2; continue; }
    IFS='|' read -r lbl prompt cfg driver <<<"$row"
    for rep in $(seq 1 "$REPS"); do
      TOTAL=$((TOTAL + 1))
      run_id="${inst//[^a-zA-Z0-9_-]/_}_rep${rep}"
      RUN_DIR="$EXP_DIR/runs/$lbl/run_$run_id"

      if [[ -f "$RUN_DIR/result.json" ]]; then
        echo "[$TOTAL] $lbl / $run_id — already done, skipping"
        continue
      fi

      echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
      echo "[$TOTAL] $lbl / $inst / rep$rep  (prompt=$prompt cfg=$cfg driver=$driver)"
      echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

      RUN_ARGS=(--instance "$inst" --driver "$driver" --prompt "$prompt")
      if [[ "$cfg" != "none" ]]; then
        RUN_ARGS+=(--succession-config "$cfg")
      fi

      "$SCRIPT_DIR/02-run.sh" "$lbl" "$run_id" "${RUN_ARGS[@]}"
      "$SCRIPT_DIR/03-evaluate.sh" "$lbl" "$run_id"
    done
  done
done

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "Running analyzer..."
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
"$SCRIPT_DIR/05-analyze.sh"
