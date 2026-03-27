#!/usr/bin/env bash
# SOUL Framework — Rolling Compaction (PostCompact Hook)
# Reads the session transcript, extracts recent context, and merges it into SOUL.md
# Input: JSON on stdin with session_id, cwd, trigger, transcript_path
# Output: None (updates SOUL.md directly)

set -euo pipefail

INPUT=$(cat)
CWD=$(echo "$INPUT" | jq -r '.cwd // ""')
SOUL_DIR="${CWD}/.soul"
SOUL_FILE="${SOUL_DIR}/SOUL.md"
CONFIG_FILE="${SOUL_DIR}/config.json"
LOG_DIR="${SOUL_DIR}/log"

# Bail if no .soul directory or SOUL.md
if [ ! -d "$SOUL_DIR" ] || [ ! -f "$SOUL_FILE" ]; then
  exit 0
fi

# --- Read config ---
if [ -f "$CONFIG_FILE" ]; then
  AUTO_COMMIT=$(jq -r '.compaction.autoCommit // true' "$CONFIG_FILE")
  COMPACT_MODEL=$(jq -r '.compaction.model // "sonnet"' "$CONFIG_FILE")
else
  AUTO_COMMIT="true"
  COMPACT_MODEL="sonnet"
fi

# Map config model name to Claude model ID
case "$COMPACT_MODEL" in
  haiku)   COMPACT_MODEL_ID="claude-haiku-4-5-20251001" ;;
  sonnet)  COMPACT_MODEL_ID="claude-sonnet-4-6" ;;
  opus)    COMPACT_MODEL_ID="claude-opus-4-6" ;;
  *)       COMPACT_MODEL_ID="claude-haiku-4-5-20251001" ;;
esac

# --- Read transcript path ---
TRANSCRIPT_PATH=$(echo "$INPUT" | jq -r '.transcript_path // ""')

# Build a session summary from available sources
SESSION_SUMMARY=""

# Try to extract recent context from the transcript
if [ -n "$TRANSCRIPT_PATH" ] && [ -f "$TRANSCRIPT_PATH" ]; then
  # Extract the last ~50 entries from the transcript for context
  # Focus on assistant messages which contain the work done
  SESSION_SUMMARY=$(tail -100 "$TRANSCRIPT_PATH" | jq -r '
    select(.type == "assistant" or .type == "tool_result")
    | if .type == "assistant" then
        .message.content[] | select(.type == "text") | .text
      elif .type == "tool_result" then
        .content // empty
      else empty end
  ' 2>/dev/null | head -c 8000) || true
fi

# Also try to get git log of recent changes in this session
GIT_SUMMARY=""
if command -v git &>/dev/null && git -C "$CWD" rev-parse --git-dir &>/dev/null 2>&1; then
  GIT_SUMMARY=$(git -C "$CWD" log --oneline --no-decorate -10 2>/dev/null) || true
fi

# If we have nothing to work with, skip compaction
if [ -z "$SESSION_SUMMARY" ] && [ -z "$GIT_SUMMARY" ]; then
  exit 0
fi

# --- Read current SOUL.md ---
CURRENT_SOUL=$(cat "$SOUL_FILE")

# --- Build compaction prompt ---
COMPACT_PROMPT="You are a soul compaction system for the SOUL framework. Your job is to merge new session knowledge into an existing SOUL.md file.

CURRENT SOUL.MD:
${CURRENT_SOUL}

SESSION CONTEXT (recent transcript excerpts):
${SESSION_SUMMARY}

RECENT GIT HISTORY:
${GIT_SUMMARY}

INSTRUCTIONS:
1. Merge any new knowledge from the session into the appropriate sections of SOUL.md
2. Update 'Accumulated Knowledge' with confirmed patterns, decisions, and discoveries
3. Update 'Predecessor Warnings' if new failure modes were encountered
4. Update 'Current Understanding' to reflect the latest state of the codebase/task
5. Resolve contradictions — newer information wins, but note the contradiction briefly
6. PRUNE information that is stale, redundant, or no longer relevant
7. Keep the document concise — compaction means compression, not accumulation
8. Preserve the existing section structure (Identity, Accumulated Knowledge, Predecessor Warnings, Current Understanding, Skills)
9. Do NOT add sections that don't exist in the original
10. Do NOT remove the ## Skills section or modify skill definitions unless the session explicitly changed them

Output ONLY the updated SOUL.md content. No markdown fencing, no preamble, no explanation. Just the raw markdown content of the updated file."

# --- Run compaction via claude -p ---
# claude -p --output-format json outputs a JSON array; the result text is in the last element's .result field
RAW_OUTPUT=$(echo "$COMPACT_PROMPT" | timeout 60 claude -p --model "$COMPACT_MODEL_ID" --output-format json 2>/dev/null) || true
UPDATED_SOUL=$(echo "$RAW_OUTPUT" | jq -r 'if type == "array" then .[-1].result // empty else .result // . end' 2>/dev/null) || true

# Validate we got something reasonable back
if [ -z "$UPDATED_SOUL" ] || [ ${#UPDATED_SOUL} -lt 50 ]; then
  # Compaction failed or returned garbage — keep existing soul
  mkdir -p "$LOG_DIR"
  jq -cn \
    --arg ts "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    --arg error "Compaction returned empty or too-short result" \
    '{timestamp: $ts, event: "compaction_failed", error: $error}' \
    >> "${LOG_DIR}/conscience.jsonl"
  exit 0
fi

# Validate it still has the expected structure
if ! echo "$UPDATED_SOUL" | grep -q "## Identity"; then
  mkdir -p "$LOG_DIR"
  jq -cn \
    --arg ts "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    --arg error "Compaction result missing ## Identity section" \
    '{timestamp: $ts, event: "compaction_rejected", error: $error}' \
    >> "${LOG_DIR}/conscience.jsonl"
  exit 0
fi

# --- Write updated SOUL.md ---
echo "$UPDATED_SOUL" > "$SOUL_FILE"

# --- Log the compaction ---
mkdir -p "$LOG_DIR"
jq -cn \
  --arg ts "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  --arg trigger "$(echo "$INPUT" | jq -r '.trigger // "unknown"')" \
  --argjson old_size "${#CURRENT_SOUL}" \
  --argjson new_size "${#UPDATED_SOUL}" \
  '{timestamp: $ts, event: "compaction", trigger: $trigger, old_size_bytes: $old_size, new_size_bytes: $new_size}' \
  >> "${LOG_DIR}/conscience.jsonl"

# --- Auto-commit if enabled and in a git repo ---
if [ "$AUTO_COMMIT" = "true" ] && command -v git &>/dev/null; then
  if git -C "$CWD" rev-parse --git-dir &>/dev/null 2>&1; then
    git -C "$CWD" add "$SOUL_FILE" 2>/dev/null || true
    git -C "$CWD" commit -m "soul: rolling compaction" --no-verify 2>/dev/null || true
  fi
fi

exit 0
