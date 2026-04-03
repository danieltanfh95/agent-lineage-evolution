#!/usr/bin/env bash
# Imprint — SessionStart Hook
# Compiles rules via cascade resolution and injects advisory rules + skills as additionalContext.
# Input: JSON on stdin with session_id, cwd, source, etc.
# Output: JSON with hookSpecificOutput.additionalContext

set -euo pipefail

INPUT=$(cat)
CWD=$(echo "$INPUT" | jq -r '.cwd // ""')
SESSION_ID=$(echo "$INPUT" | jq -r '.session_id // "unknown"')

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/lib.sh"

LOG_DIR="${CWD}/.imprint/log"
COMPILED_DIR="${CWD}/.imprint/compiled"

# Bail if no .imprint directory in this repo AND no global rules
if [ ! -d "${CWD}/.imprint" ] && [ ! -d "${IMPRINT_GLOBAL_DIR}/rules" ]; then
  exit 0
fi

mkdir -p "${CWD}/.imprint/rules" "$COMPILED_DIR" "$LOG_DIR"

# --- Log rotation ---
rotate_log_if_needed

# --- Phase 1: Compile rules (cascade resolution) ---
"${SCRIPT_DIR}/imprint-resolve.sh" "$CWD" 2>/dev/null || true

# --- Phase 2: Assemble additionalContext ---
assembled=""

# Include advisory summary
ADVISORY_FILE="${COMPILED_DIR}/advisory-summary.md"
if [ -f "$ADVISORY_FILE" ] && [ -s "$ADVISORY_FILE" ]; then
  assembled+="
--- IMPRINT: ACTIVE RULES ---
$(cat "$ADVISORY_FILE")
"
fi

# Include semantic rules summary (so the agent is aware of them even though they're enforced by prompt hook)
SEMANTIC_FILE="${COMPILED_DIR}/semantic-rules.md"
if [ -f "$SEMANTIC_FILE" ] && [ -s "$SEMANTIC_FILE" ]; then
  assembled+="
--- IMPRINT: SEMANTIC RULES (enforced on tool calls) ---
$(cat "$SEMANTIC_FILE")
"
fi

# --- Phase 3: Load skills (project then global, project wins) ---
LOADED_SKILLS=""

load_skills_from() {
  local skills_dir="$1"
  local scope="$2"
  [ -d "$skills_dir" ] || return

  for skill_dir in "$skills_dir"/*/; do
    [ -d "$skill_dir" ] || continue
    local skill_name
    skill_name=$(basename "$skill_dir")
    local skill_file="${skill_dir}/SKILL.md"
    [ -f "$skill_file" ] || continue

    # Skip if already loaded (project wins over global)
    if echo "$LOADED_SKILLS" | grep -q "|${skill_name}|"; then
      continue
    fi

    # Install skill into .claude/skills/ for Claude Code to pick up
    local target_dir="${CWD}/.claude/skills/${skill_name}"
    mkdir -p "$target_dir"
    cp "$skill_file" "${target_dir}/SKILL.md"

    LOADED_SKILLS="${LOADED_SKILLS}|${skill_name}|"
  done
}

# Project skills first (higher priority)
load_skills_from "${CWD}/.imprint/skills" "project"
# Global skills second (lower priority)
load_skills_from "${IMPRINT_GLOBAL_DIR}/skills" "global"

# --- Phase 4: Conflict resolution note ---
assembled+="
--- IMPRINT: RULE RESOLUTION ---
Rules are resolved by cascade: project-level (.imprint/rules/) overrides global (~/.imprint/rules/).
Rules with explicit 'overrides' fields cancel the referenced rules.
Higher priority numbers win among rules at the same scope level."

# --- Log session start ---
RULE_COUNT=0
if [ -f "${COMPILED_DIR}/tool-rules.json" ]; then
  RULE_COUNT=$(jq 'length' "${COMPILED_DIR}/tool-rules.json" 2>/dev/null) || RULE_COUNT=0
fi

log_imprint_event "session_start" \
  --argjson mechanical_rules "$RULE_COUNT" \
  --arg skills_loaded "$LOADED_SKILLS"

# --- Output ---
if [ -n "$assembled" ]; then
  jq -n --arg context "$assembled" '{
    hookSpecificOutput: {
      hookEventName: "SessionStart",
      additionalContext: $context
    }
  }'
else
  exit 0
fi
