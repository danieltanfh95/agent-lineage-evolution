#!/usr/bin/env bash
# Experiment 01 — Run Trial
#
# Usage: ./run-trial.sh <treatment|control> <trial-number> [target-dir]
#
# Runs one trial of the compaction vs. append-only experiment.
# For treatment: uses SOUL compaction
# For control: uses append-only MEMORY.md
#
# This script sets up the environment and provides instructions
# for the human operator to execute each session. Full automation
# would require Claude API scripting, so this provides a structured
# protocol for manual execution.

set -euo pipefail

MODE="${1:?Usage: ./run-trial.sh <treatment|control> <trial-number>}"
TRIAL="${2:?Usage: ./run-trial.sh <treatment|control> <trial-number>}"
TARGET_DIR="${3:-/tmp/soul-experiment-01}"
RESULTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/results"

if [[ "$MODE" != "treatment" && "$MODE" != "control" ]]; then
  echo "Error: mode must be 'treatment' or 'control'"
  exit 1
fi

mkdir -p "${RESULTS_DIR}"

echo "=== Experiment 01: Compaction vs. Append-Only ==="
echo "Mode: ${MODE}"
echo "Trial: ${TRIAL}"
echo "Target: ${TARGET_DIR}"
echo ""

# Step 1: Fresh setup
echo "Step 1: Creating fresh test repo..."
"$(dirname "${BASH_SOURCE[0]}")/setup.sh" "${TARGET_DIR}"

if [[ "$MODE" == "treatment" ]]; then
  echo ""
  echo "Step 2: Initializing SOUL framework..."
  # Copy soul-init.sh to target and run it
  SCRIPT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
  cd "${TARGET_DIR}"
  bash "${SCRIPT_ROOT}/soul-init.sh"
  echo ""
  echo "SOUL initialized. Compaction will run on PostCompact events."
elif [[ "$MODE" == "control" ]]; then
  echo ""
  echo "Step 2: Creating append-only MEMORY.md..."
  echo "# Memory" > "${TARGET_DIR}/MEMORY.md"
  echo "" >> "${TARGET_DIR}/MEMORY.md"
  echo "<!-- Append-only: new observations are added below, nothing is removed -->" >> "${TARGET_DIR}/MEMORY.md"
  echo ""
  echo "Append-only MEMORY.md created. No compaction will occur."
fi

echo ""
echo "================================================================"
echo "MANUAL EXECUTION PROTOCOL"
echo "================================================================"
echo ""
echo "For each of the 10 sessions below, open a Claude Code session"
echo "in ${TARGET_DIR} and instruct the agent to make the change."
echo ""
echo "After each session, the agent's memory file will be updated:"
echo "  Treatment: SOUL.md is compacted automatically"
echo "  Control: Append observations to MEMORY.md manually"
echo ""
echo "Sessions:"
echo "  1. Add PUT /api/users/:id endpoint"
echo "  2. Fix bug — createUser missing input validation"
echo "  3. Replace lodash with native JS methods"
echo "  4. Add DELETE /api/users/:id endpoint"
echo "  5. Change database from pg to better-sqlite3"
echo "  6. Fix bug — healthCheck should include uptime"
echo "  7. Add GET /api/users/:id endpoint"
echo "  8. Refactor — split routes into separate router files"
echo "  9. Replace express with Hono framework"
echo "  10. Add authentication middleware (JWT)"
echo ""
echo "After all 10 sessions, snapshot the memory file:"
SNAPSHOT="${RESULTS_DIR}/${MODE}-trial${TRIAL}-snapshot.md"
echo "  cp <memory-file> ${SNAPSHOT}"
echo ""
echo "Then evaluate using evaluation-rubric.md and record results in:"
echo "  ${RESULTS_DIR}/${MODE}-trial${TRIAL}-scores.json"
echo ""
echo "Expected JSON format:"
echo '  {'
echo '    "trial": '"${TRIAL}"','
echo '    "mode": "'"${MODE}"'",'
echo '    "contradictions": <count>,'
echo '    "staleness": <count>,'
echo '    "file_size_bytes": <bytes>,'
echo '    "accuracy_score": <0-10>'
echo '  }'
