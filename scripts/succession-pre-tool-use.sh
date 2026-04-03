#!/usr/bin/env bash
# Succession — PreToolUse Enforcement Hook (Mechanical Tier)
# Blocks tool calls that violate compiled mechanical rules.
# Input: JSON on stdin with tool_name, tool_input, cwd, session_id, transcript_path
# Output: {"decision": "block", "reason": "..."} or exit 0 to allow
# MUST BE FAST — no LLM calls. Pure bash + jq only.

set -euo pipefail

INPUT=$(cat)
CWD=$(echo "$INPUT" | jq -r '.cwd // ""')
SESSION_ID=$(echo "$INPUT" | jq -r '.session_id // "unknown"')
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/lib.sh"

COMPILED_DIR="${CWD}/.succession/compiled"
TOOL_RULES_FILE="${COMPILED_DIR}/tool-rules.json"

# Also check global compiled rules as fallback
GLOBAL_RULES_FILE="${HOME}/.succession/compiled/tool-rules.json"

# Find the first available rules file
if [ -f "$TOOL_RULES_FILE" ]; then
  RULES_FILE="$TOOL_RULES_FILE"
elif [ -f "$GLOBAL_RULES_FILE" ]; then
  RULES_FILE="$GLOBAL_RULES_FILE"
else
  exit 0  # No rules to enforce
fi

TOOL_NAME=$(echo "$INPUT" | jq -r '.tool_name // ""')

# --- Check block_tool rules ---
BLOCK_REASON=$(jq -r --arg tool "$TOOL_NAME" \
  '.[] | select(.block_tool == $tool) | .reason' \
  "$RULES_FILE" 2>/dev/null | head -1)

if [ -n "$BLOCK_REASON" ]; then
  BLOCKED_SOURCE=$(jq -r --arg tool "$TOOL_NAME" '.[] | select(.block_tool == $tool) | .source' "$RULES_FILE" 2>/dev/null | head -1)
  log_meta_cognition_event "rule_violated" --arg rule_id "${BLOCKED_SOURCE:-unknown}" --arg context "block_tool:${TOOL_NAME}" --arg detected_by "pre-tool-use" &
  jq -n --arg reason "Succession: ${BLOCK_REASON}" '{"decision": "block", "reason": $reason}'
  exit 0
fi

# --- Check block_bash_pattern rules (only for Bash tool) ---
if [ "$TOOL_NAME" = "Bash" ]; then
  COMMAND=$(echo "$INPUT" | jq -r '.tool_input.command // ""')
  if [ -n "$COMMAND" ]; then
    while IFS= read -r rule; do
      pattern=$(echo "$rule" | jq -r '.block_bash_pattern')
      reason=$(echo "$rule" | jq -r '.reason')
      source_id=$(echo "$rule" | jq -r '.source')
      if echo "$COMMAND" | grep -qE "$pattern" 2>/dev/null; then
        log_meta_cognition_event "rule_violated" --arg rule_id "${source_id:-unknown}" --arg context "bash:$(echo "$COMMAND" | head -c 100)" --arg detected_by "pre-tool-use" &
        jq -n --arg reason "Succession: ${reason}" '{"decision": "block", "reason": $reason}'
        exit 0
      fi
    done < <(jq -c '.[] | select(.block_bash_pattern != null)' "$RULES_FILE" 2>/dev/null)
  fi
fi

# --- Check require_prior_read rules (only for Edit tool) ---
if [ "$TOOL_NAME" = "Edit" ]; then
  HAS_READ_RULE=$(jq -r '.[] | select(.require_prior_read == true) | .reason' "$RULES_FILE" 2>/dev/null | head -1)
  if [ -n "$HAS_READ_RULE" ]; then
    TARGET_FILE=$(echo "$INPUT" | jq -r '.tool_input.file_path // ""')
    TRANSCRIPT_PATH=$(echo "$INPUT" | jq -r '.transcript_path // ""')
    if [ -n "$TARGET_FILE" ] && [ -n "$TRANSCRIPT_PATH" ] && [ -f "$TRANSCRIPT_PATH" ]; then
      PRIOR_READ=$(tail -200 "$TRANSCRIPT_PATH" | jq -r \
        --arg fp "$TARGET_FILE" \
        'select(.type == "tool_use" and .tool_name == "Read" and .tool_input.file_path == $fp) | .tool_name' \
        2>/dev/null | head -1)
      if [ -z "$PRIOR_READ" ]; then
        READ_SOURCE=$(jq -r '.[] | select(.require_prior_read == true) | .source' "$RULES_FILE" 2>/dev/null | head -1)
        log_meta_cognition_event "rule_violated" --arg rule_id "${READ_SOURCE:-unknown}" --arg context "edit-without-read:${TARGET_FILE}" --arg detected_by "pre-tool-use" &
        jq -n --arg reason "Succession: ${HAS_READ_RULE}" '{"decision": "block", "reason": $reason}'
        exit 0
      fi
    fi
  fi
fi

# All checks passed — log rule_followed for each evaluated rule (async, non-blocking)
{
  while IFS= read -r source_id; do
    [ -n "$source_id" ] && log_meta_cognition_event "rule_followed" --arg rule_id "$source_id" --arg detected_by "pre-tool-use"
  done < <(jq -r '.[].source' "$RULES_FILE" 2>/dev/null)
} &
exit 0
