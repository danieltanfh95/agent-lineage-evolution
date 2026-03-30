#!/usr/bin/env bash
# SOUL Framework — Shared Hook Library
# Sourced by conscience.sh, compact.sh, and session-start.sh
# Provides: map_model_id(), log_soul_event()

# Map friendly model names to Claude model IDs
map_model_id() {
  case "$1" in
    haiku)   echo "claude-haiku-4-5-20251001" ;;
    sonnet)  echo "claude-sonnet-4-6" ;;
    opus)    echo "claude-opus-4-6" ;;
    *)       echo "claude-haiku-4-5-20251001" ;;
  esac
}

# Log a structured event to the unified activity log.
# Usage: log_soul_event "event_name" [jq --arg flags...]
# Example: log_soul_event "audit_pass" --argjson turn 5 --arg keyword "commit"
#
# Requires $LOG_DIR and $SESSION_ID to be set by the caller.
log_soul_event() {
  local event="$1"
  shift

  mkdir -p "$LOG_DIR"
  jq -cn \
    --arg ts "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    --arg session "${SESSION_ID:-unknown}" \
    --arg event "$event" \
    "$@" \
    '{timestamp: $ts, session: $session, event: $event} + $ARGS.named' \
    >> "${LOG_DIR}/soul-activity.jsonl" 2>/dev/null || true
}

# Rotate log file if it exceeds 1MB. Call once at session start.
# Usage: rotate_log_if_needed
rotate_log_if_needed() {
  local log_file="${LOG_DIR}/soul-activity.jsonl"
  if [ -f "$log_file" ]; then
    local size
    size=$(wc -c < "$log_file" | tr -d ' ')
    if [ "$size" -gt 1048576 ]; then
      mv "$log_file" "${log_file}.1"
    fi
  fi
}
