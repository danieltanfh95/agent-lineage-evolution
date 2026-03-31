#!/usr/bin/env bash
# SOUL Framework — PreToolUse Enforcement Hook
# Blocks tool calls that violate invariants BEFORE they execute.
# Input: JSON on stdin with tool_name, tool_input, cwd, session_id, transcript_path
# Output: {"decision": "block", "reason": "..."} or exit 0 to allow
# MUST BE FAST — no LLM calls. Pure bash + jq only.

set -euo pipefail

INPUT=$(cat)
CWD=$(echo "$INPUT" | jq -r '.cwd // ""')
SOUL_DIR="${CWD}/.soul"
TOOL_RULES_FILE="${SOUL_DIR}/invariants/tool-rules.json"

# Bail if no rules file
if [ ! -f "$TOOL_RULES_FILE" ]; then
  exit 0
fi

# Check config for enabled flag
CONFIG_FILE="${SOUL_DIR}/config.json"
if [ -f "$CONFIG_FILE" ] && jq empty "$CONFIG_FILE" 2>/dev/null; then
  ENABLED=$(jq -r '.preToolUse.enabled // true' "$CONFIG_FILE")
  if [ "$ENABLED" = "false" ]; then
    exit 0
  fi
fi

TOOL_NAME=$(echo "$INPUT" | jq -r '.tool_name // ""')

# --- Check block_tool rules ---
BLOCK_REASON=$(jq -r --arg tool "$TOOL_NAME" \
  '.[] | select(.block_tool == $tool) | .reason' \
  "$TOOL_RULES_FILE" 2>/dev/null | head -1)

if [ -n "$BLOCK_REASON" ]; then
  jq -n --arg reason "SOUL: ${BLOCK_REASON}" '{"decision": "block", "reason": $reason}'
  exit 0
fi

# --- Check block_bash_pattern rules (only for Bash tool) ---
if [ "$TOOL_NAME" = "Bash" ]; then
  COMMAND=$(echo "$INPUT" | jq -r '.tool_input.command // ""')
  if [ -n "$COMMAND" ]; then
    while IFS= read -r rule; do
      pattern=$(echo "$rule" | jq -r '.block_bash_pattern')
      reason=$(echo "$rule" | jq -r '.reason')
      if echo "$COMMAND" | grep -qE "$pattern" 2>/dev/null; then
        jq -n --arg reason "SOUL: ${reason}" '{"decision": "block", "reason": $reason}'
        exit 0
      fi
    done < <(jq -c '.[] | select(.block_bash_pattern != null)' "$TOOL_RULES_FILE" 2>/dev/null)
  fi
fi

# --- Check require_prior_read rules (only for Edit tool) ---
if [ "$TOOL_NAME" = "Edit" ]; then
  HAS_READ_RULE=$(jq -r '.[] | select(.require_prior_read == true) | .reason' "$TOOL_RULES_FILE" 2>/dev/null | head -1)
  if [ -n "$HAS_READ_RULE" ]; then
    TARGET_FILE=$(echo "$INPUT" | jq -r '.tool_input.file_path // ""')
    TRANSCRIPT_PATH=$(echo "$INPUT" | jq -r '.transcript_path // ""')
    if [ -n "$TARGET_FILE" ] && [ -n "$TRANSCRIPT_PATH" ] && [ -f "$TRANSCRIPT_PATH" ]; then
      # Check last 200 lines of transcript for a Read of this file
      PRIOR_READ=$(tail -200 "$TRANSCRIPT_PATH" | jq -r \
        --arg fp "$TARGET_FILE" \
        'select(.type == "tool_use" and .tool_name == "Read" and .tool_input.file_path == $fp) | .tool_name' \
        2>/dev/null | head -1)
      if [ -z "$PRIOR_READ" ]; then
        jq -n --arg reason "SOUL: ${HAS_READ_RULE}" '{"decision": "block", "reason": $reason}'
        exit 0
      fi
    fi
  fi
fi

# All checks passed
exit 0
