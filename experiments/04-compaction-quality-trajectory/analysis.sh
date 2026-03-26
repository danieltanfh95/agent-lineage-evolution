#!/usr/bin/env bash
# Experiment 04 — Analysis Script
#
# Usage: ./analysis.sh [results-file]
#
# Reads a JSONL file of per-cycle scores and produces:
# 1. A tabular summary
# 2. Trend indicators (improving/stable/degrading) for each metric
#
# Input format: one JSON object per line (see evaluation-rubric.md recording template)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RESULTS_FILE="${1:-${SCRIPT_DIR}/results/scores.jsonl}"

if [ ! -f "${RESULTS_FILE}" ]; then
  echo "Error: Results file not found: ${RESULTS_FILE}"
  echo "Usage: ./analysis.sh [results-file]"
  echo ""
  echo "Expected format (JSONL, one object per line):"
  echo '  {"cycle":1,"commit":"abc123","date":"2026-03-25","file_size_bytes":500,"section_completeness":1,"accuracy_pct":60,"contradiction_count":2,"info_density":3.5}'
  exit 1
fi

TOTAL_CYCLES=$(wc -l < "${RESULTS_FILE}" | tr -d ' ')

echo "=== Compaction Quality Trajectory Analysis ==="
echo "Cycles: ${TOTAL_CYCLES}"
echo ""

# Print table header
printf "%-6s %-12s %-10s %-12s %-11s %-15s %-12s\n" \
  "Cycle" "Date" "Size(B)" "Sections" "Accuracy%" "Contradictions" "Density"
printf "%-6s %-12s %-10s %-12s %-11s %-15s %-12s\n" \
  "-----" "----------" "--------" "--------" "---------" "--------------" "-------"

while IFS= read -r line; do
  CYCLE=$(echo "$line" | jq -r '.cycle')
  DATE=$(echo "$line" | jq -r '.date')
  SIZE=$(echo "$line" | jq -r '.file_size_bytes')
  SECTIONS=$(echo "$line" | jq -r '.section_completeness')
  ACCURACY=$(echo "$line" | jq -r '.accuracy_pct')
  CONTRADICTIONS=$(echo "$line" | jq -r '.contradiction_count')
  DENSITY=$(echo "$line" | jq -r '.info_density')

  printf "%-6s %-12s %-10s %-12s %-11s %-15s %-12s\n" \
    "$CYCLE" "$DATE" "$SIZE" "${SECTIONS}/5" "${ACCURACY}%" "$CONTRADICTIONS" "$DENSITY"
done < "${RESULTS_FILE}"

echo ""
echo "=== Trend Analysis ==="

# Compute simple trends (compare first third vs last third)
THIRD=$((TOTAL_CYCLES / 3))
if [ "$THIRD" -lt 1 ]; then THIRD=1; fi

compute_trend() {
  local field="$1"
  local label="$2"
  local higher_is_better="$3"

  EARLY_AVG=$(head -n "$THIRD" "${RESULTS_FILE}" | jq -s "[.[].$field] | add / length")
  LATE_AVG=$(tail -n "$THIRD" "${RESULTS_FILE}" | jq -s "[.[].$field] | add / length")

  DIFF=$(echo "$LATE_AVG - $EARLY_AVG" | bc -l)

  if [ "$higher_is_better" = "true" ]; then
    if (( $(echo "$DIFF > 0.5" | bc -l) )); then
      TREND="IMPROVING ↑"
    elif (( $(echo "$DIFF < -0.5" | bc -l) )); then
      TREND="DEGRADING ↓"
    else
      TREND="STABLE →"
    fi
  else
    if (( $(echo "$DIFF < -0.5" | bc -l) )); then
      TREND="IMPROVING ↑"
    elif (( $(echo "$DIFF > 0.5" | bc -l) )); then
      TREND="DEGRADING ↓"
    else
      TREND="STABLE →"
    fi
  fi

  printf "  %-20s Early avg: %6.1f  Late avg: %6.1f  %s\n" "$label" "$EARLY_AVG" "$LATE_AVG" "$TREND"
}

compute_trend "file_size_bytes" "File size" "false"
compute_trend "section_completeness" "Completeness" "true"
compute_trend "accuracy_pct" "Accuracy" "true"
compute_trend "contradiction_count" "Contradictions" "false"
compute_trend "info_density" "Info density" "true"

echo ""
echo "Note: Trends compare the average of the first ${THIRD} cycles vs. the last ${THIRD} cycles."
echo "A more rigorous analysis would use linear regression over all data points."
