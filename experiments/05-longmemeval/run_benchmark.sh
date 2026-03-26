#!/usr/bin/env bash
# SOUL-LongMemEval Benchmark Runner
# Orchestrates dataset download, adapter runs, evaluation, and analysis.
#
# Usage:
#   ./run_benchmark.sh                    # Run all conditions
#   ./run_benchmark.sh --limit 5          # Quick test with 5 instances
#   ./run_benchmark.sh --config haiku     # Run a single condition
#   ./run_benchmark.sh --eval-only        # Skip adapter, just run evaluation

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
DATA_DIR="${SCRIPT_DIR}/data"
RESULTS_DIR="${SCRIPT_DIR}/results"
EVAL_DIR="${SCRIPT_DIR}/LongMemEval"

LIMIT=""
SINGLE_CONFIG=""
EVAL_ONLY=false

# Parse arguments
while [[ $# -gt 0 ]]; do
  case $1 in
    --limit) LIMIT="--limit $2"; shift 2 ;;
    --config) SINGLE_CONFIG="$2"; shift 2 ;;
    --eval-only) EVAL_ONLY=true; shift ;;
    *) echo "Unknown option: $1"; exit 1 ;;
  esac
done

# --- Step 1: Download LongMemEval dataset ---
echo "=== Step 1: Checking LongMemEval dataset ==="
mkdir -p "$DATA_DIR"

if [ ! -f "${DATA_DIR}/longmemeval_s_cleaned.json" ]; then
  echo "Downloading longmemeval_s_cleaned.json from HuggingFace..."
  if command -v python3 &>/dev/null; then
    python3 -c "
from huggingface_hub import hf_hub_download
path = hf_hub_download(
    repo_id='xiaowu0162/longmemeval-cleaned',
    filename='longmemeval_s_cleaned.json',
    repo_type='dataset',
    local_dir='${DATA_DIR}'
)
print(f'Downloaded to {path}')
"
  else
    echo "ERROR: python3 required to download dataset"
    exit 1
  fi
else
  echo "Dataset already present at ${DATA_DIR}/longmemeval_s_cleaned.json"
fi

# Also download oracle data for evaluation
if [ ! -f "${DATA_DIR}/longmemeval_oracle.json" ]; then
  echo "Downloading longmemeval_oracle.json..."
  python3 -c "
from huggingface_hub import hf_hub_download
path = hf_hub_download(
    repo_id='xiaowu0162/longmemeval-cleaned',
    filename='longmemeval_oracle.json',
    repo_type='dataset',
    local_dir='${DATA_DIR}'
)
print(f'Downloaded to {path}')
"
fi

# --- Step 2: Clone LongMemEval eval scripts ---
echo ""
echo "=== Step 2: Checking LongMemEval evaluation scripts ==="
if [ ! -d "$EVAL_DIR" ]; then
  echo "Cloning LongMemEval repository..."
  git clone https://github.com/xiaowu0162/LongMemEval.git "$EVAL_DIR"
else
  echo "LongMemEval repo already present at ${EVAL_DIR}"
fi

# --- Step 3: Run adapter for each condition ---
if [ "$EVAL_ONLY" = false ]; then
  echo ""
  echo "=== Step 3: Running SOUL adapter ==="

  DATA_FILE="${DATA_DIR}/longmemeval_s_cleaned.json"

  if [ -n "$SINGLE_CONFIG" ]; then
    CONFIGS=("$SINGLE_CONFIG")
  else
    CONFIGS=("no-memory" "haiku" "sonnet" "haiku-every5")
    # Note: opus and full-context are expensive — uncomment to include:
    # CONFIGS=("no-memory" "haiku" "sonnet" "opus" "haiku-every5" "full-context")
  fi

  for config_name in "${CONFIGS[@]}"; do
    config_path="${SCRIPT_DIR}/configs/${config_name}.json"
    if [ ! -f "$config_path" ]; then
      echo "WARNING: Config not found: ${config_path}, skipping"
      continue
    fi

    echo ""
    echo "--- Running condition: ${config_name} ---"
    python3 "${SCRIPT_DIR}/adapter.py" \
      --config "$config_path" \
      --data "$DATA_FILE" \
      --output-dir "$RESULTS_DIR" \
      --resume \
      $LIMIT
  done
fi

# --- Step 4: Run LongMemEval evaluation ---
echo ""
echo "=== Step 4: Running LongMemEval evaluation ==="

ORACLE_FILE="${DATA_DIR}/longmemeval_oracle.json"
EVAL_SCRIPT="${EVAL_DIR}/evaluation/evaluate_qa.py"

if [ ! -f "$EVAL_SCRIPT" ]; then
  echo "WARNING: evaluate_qa.py not found at ${EVAL_SCRIPT}"
  echo "Skipping evaluation. Run manually after cloning LongMemEval."
else
  for results_subdir in "${RESULTS_DIR}"/*/; do
    config_name=$(basename "$results_subdir")
    pred_file="${results_subdir}predictions.jsonl"

    if [ ! -f "$pred_file" ]; then
      echo "No predictions for ${config_name}, skipping"
      continue
    fi

    echo "Evaluating ${config_name}..."
    # LongMemEval's evaluate_qa.py uses GPT-4o by default for judgment
    python3 "$EVAL_SCRIPT" gpt-4o "$pred_file" "$ORACLE_FILE" || {
      echo "WARNING: Evaluation failed for ${config_name}"
    }
  done

  # Print metrics
  METRICS_SCRIPT="${EVAL_DIR}/evaluation/print_qa_metrics.py"
  if [ -f "$METRICS_SCRIPT" ]; then
    echo ""
    echo "=== Evaluation Results ==="
    for results_subdir in "${RESULTS_DIR}"/*/; do
      config_name=$(basename "$results_subdir")
      log_file="${results_subdir}predictions.jsonl.log"
      if [ -f "$log_file" ]; then
        echo ""
        echo "--- ${config_name} ---"
        python3 "$METRICS_SCRIPT" "$log_file"
      fi
    done
  fi
fi

# --- Step 5: Run cost/latency analysis ---
echo ""
echo "=== Step 5: Running cost/latency analysis ==="
python3 "${SCRIPT_DIR}/analyze_results.py" "$RESULTS_DIR"

echo ""
echo "=== Benchmark complete ==="
echo "Results in: ${RESULTS_DIR}/"
