#!/usr/bin/env bash
# SOUL Framework — Rolling Compaction (PostCompact Hook)
# Two compaction targets:
#   1. .soul/SOUL.md — repo-specific knowledge (always)
#   2. ~/.soul/genome/learned.md — cross-project patterns (when over size threshold)
# Validation: section-level bullet count comparison, diff saving, optional approval gate
# Input: JSON on stdin with session_id, cwd, trigger, transcript_path
# Output: None (PostCompact stdout is discarded — notifications relay via temp file to Stop hook)

set -euo pipefail

INPUT=$(cat)
CWD=$(echo "$INPUT" | jq -r '.cwd // ""')
SOUL_DIR="${CWD}/.soul"
SOUL_FILE="${SOUL_DIR}/SOUL.md"
CONFIG_FILE="${SOUL_DIR}/config.json"
LOG_DIR="${SOUL_DIR}/log"
STAGING_DIR="${SOUL_DIR}/staging"

# Bail if no .soul directory or SOUL.md
if [ ! -d "$SOUL_DIR" ] || [ ! -f "$SOUL_FILE" ]; then
  exit 0
fi

# --- Source shared library (with fallback stubs if missing) ---
if [ -f "${SOUL_DIR}/hooks/lib.sh" ]; then
  source "${SOUL_DIR}/hooks/lib.sh"
fi
if ! type map_model_id &>/dev/null; then
  map_model_id() { echo "claude-haiku-4-5-20251001"; }
  log_soul_event() { :; }
  rotate_log_if_needed() { :; }
fi

# --- Session tracking ---
SESSION_ID=$(echo "$INPUT" | jq -r '.session_id // "unknown"')

# --- Read config (validate JSON first to avoid set -e crash) ---
if [ -f "$CONFIG_FILE" ] && jq empty "$CONFIG_FILE" 2>/dev/null; then
  AUTO_COMMIT=$(jq -r '.compaction.autoCommit // true' "$CONFIG_FILE")
  COMPACT_MODEL=$(jq -r '.compaction.model // .conscience.model // "haiku"' "$CONFIG_FILE")
  REQUIRE_APPROVAL=$(jq -r '.compaction.requireApproval // false' "$CONFIG_FILE")
  MAX_BULLET_LOSS=$(jq -r '.compaction.maxBulletLossPercent // 50' "$CONFIG_FILE")
else
  AUTO_COMMIT="true"
  COMPACT_MODEL="haiku"
  REQUIRE_APPROVAL="false"
  MAX_BULLET_LOSS=50
fi

COMPACT_MODEL_ID=$(map_model_id "$COMPACT_MODEL")

# --- Helper: count bullets per section ---
# Output format: "Section Name:count" per line
count_section_bullets() {
  local content="$1"
  local sections=("Accumulated Knowledge" "Predecessor Warnings" "Current Understanding" "Identity" "Skills")

  for section in "${sections[@]}"; do
    local count
    count=$(echo "$content" | sed -n "/^## ${section}$/,/^## /{/^- /p}" | wc -l | tr -d ' ')
    echo "${section}:${count}"
  done
}

# --- Read transcript path ---
TRANSCRIPT_PATH=$(echo "$INPUT" | jq -r '.transcript_path // ""')

# Build a session summary from available sources
SESSION_SUMMARY=""

# Try to extract recent context from the transcript
if [ -n "$TRANSCRIPT_PATH" ] && [ -f "$TRANSCRIPT_PATH" ]; then
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

# --- Read current SOUL.md and count bullets BEFORE compaction ---
CURRENT_SOUL=$(cat "$SOUL_FILE")
BEFORE_COUNTS=$(count_section_bullets "$CURRENT_SOUL")

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
RAW_OUTPUT=$(echo "$COMPACT_PROMPT" | timeout 60 claude -p --model "$COMPACT_MODEL_ID" --output-format json 2>/dev/null) || true
UPDATED_SOUL=$(echo "$RAW_OUTPUT" | jq -r 'if type == "array" then .[-1].result // empty else .result // . end' 2>/dev/null) || true

# --- Basic validation ---
if [ -z "$UPDATED_SOUL" ] || [ ${#UPDATED_SOUL} -lt 50 ]; then
  log_soul_event "compaction_rejected" \
    --arg reason "empty_or_short" \
    --arg error "Compaction returned empty or too-short result"
  exit 0
fi

if ! echo "$UPDATED_SOUL" | grep -q "## Identity"; then
  log_soul_event "compaction_rejected" \
    --arg reason "missing_identity" \
    --arg error "Compaction result missing ## Identity section"
  exit 0
fi

# --- Section-level bullet count validation ---
AFTER_COUNTS=$(count_section_bullets "$UPDATED_SOUL")
BULLET_LOSS_REJECTED=false

while IFS=: read -r section before_count; do
  after_count=$(echo "$AFTER_COUNTS" | grep "^${section}:" | cut -d: -f2)
  after_count=${after_count:-0}
  before_count=${before_count:-0}

  if [ "$before_count" -gt 0 ]; then
    loss_pct=$(( (before_count - after_count) * 100 / before_count ))
    if [ "$loss_pct" -gt "$MAX_BULLET_LOSS" ]; then
      BULLET_LOSS_REJECTED=true
      log_soul_event "compaction_rejected" \
        --arg reason "bullet_loss" \
        --arg section "$section" \
        --argjson before "$before_count" \
        --argjson after "$after_count" \
        --argjson loss_pct "$loss_pct"
    fi
  fi
done <<< "$BEFORE_COUNTS"

if [ "$BULLET_LOSS_REJECTED" = true ]; then
  # Save rejected version for debugging
  mkdir -p "$STAGING_DIR"
  echo "$UPDATED_SOUL" > "${STAGING_DIR}/rejected-compaction.md"
  # Relay notification to Stop hook
  echo "Compaction dropped too much knowledge (>${MAX_BULLET_LOSS}% in a section). Saved for review with /soul review" \
    > "/tmp/.soul-compact-notify-${SESSION_ID}"
  exit 0
fi

# --- Save diff to staging ---
mkdir -p "$STAGING_DIR"
diff <(echo "$CURRENT_SOUL") <(echo "$UPDATED_SOUL") > "${STAGING_DIR}/last-compaction-diff.txt" 2>/dev/null || true

# Calculate sizes for notification
OLD_SIZE=${#CURRENT_SOUL}
NEW_SIZE=${#UPDATED_SOUL}
OLD_K=$(awk "BEGIN {printf \"%.1f\", $OLD_SIZE / 1000}")
NEW_K=$(awk "BEGIN {printf \"%.1f\", $NEW_SIZE / 1000}")

# --- Build bullet count summary for log (use jq for safe JSON construction) ---
BULLETS_BEFORE_JSON=$(echo "$BEFORE_COUNTS" | jq -Rc 'split(":") | {(.[0]): (.[1] | tonumber)}' | jq -sc 'add // {}') || BULLETS_BEFORE_JSON="{}"
BULLETS_AFTER_JSON=$(echo "$AFTER_COUNTS" | jq -Rc 'split(":") | {(.[0]): (.[1] | tonumber)}' | jq -sc 'add // {}') || BULLETS_AFTER_JSON="{}"

# --- Conditional write based on requireApproval ---
if [ "$REQUIRE_APPROVAL" = "true" ]; then
  echo "$UPDATED_SOUL" > "${STAGING_DIR}/pending-compaction.md"
  NOTIFY_MSG="Knowledge compressed (${OLD_K}k → ${NEW_K}k chars), waiting for your approval. Run /soul approve-compaction"
else
  echo "$UPDATED_SOUL" > "$SOUL_FILE"
  NOTIFY_MSG="Knowledge compressed (${OLD_K}k → ${NEW_K}k chars)"
fi

# --- Log the compaction ---
log_soul_event "compaction" \
  --arg trigger "$(echo "$INPUT" | jq -r '.trigger // "unknown"')" \
  --argjson old_size "$OLD_SIZE" \
  --argjson new_size "$NEW_SIZE" \
  --argjson bullets_before "$BULLETS_BEFORE_JSON" \
  --argjson bullets_after "$BULLETS_AFTER_JSON"

# ============================================================
# PHASE 2: GENOME COMPACTION (learned.md)
# Compact ~/.soul/genome/learned.md when it exceeds size threshold.
# ============================================================

GLOBAL_GENOME_DIR="${HOME}/.soul/genome"
LEARNED_FILE="${GLOBAL_GENOME_DIR}/learned.md"
GENOME_COMPACT_THRESHOLD=5000

if [ -f "$LEARNED_FILE" ]; then
  LEARNED_SIZE=$(wc -c < "$LEARNED_FILE" | tr -d ' ')

  if [ "$LEARNED_SIZE" -gt "$GENOME_COMPACT_THRESHOLD" ]; then
    CURRENT_LEARNED=$(cat "$LEARNED_FILE")

    GENOME_COMPACT_PROMPT="You are a genome compaction system for the SOUL framework. Your job is to compress a learned patterns file that has grown from accumulated pattern extractions across multiple projects and sessions.

CURRENT LEARNED PATTERNS FILE:
${CURRENT_LEARNED}

INSTRUCTIONS:
1. Deduplicate patterns that say the same thing in different words
2. Merge related patterns into single, concise statements
3. Remove patterns that are too project-specific (they belong in a repo's SOUL.md, not the global genome)
4. Preserve the file structure: # Learned Patterns header, intro line, ## Patterns section
5. Keep each pattern as a single bullet point (- prefix)
6. Aim to reduce the file to roughly half its current size while preserving all unique information
7. Organize patterns by theme (communication, process, coding style, etc.) using ### subheadings under ## Patterns

Output ONLY the updated file content. No markdown fencing, no preamble, no explanation."

    GENOME_RAW=$(echo "$GENOME_COMPACT_PROMPT" | timeout 60 claude -p --model "$COMPACT_MODEL_ID" --output-format json 2>/dev/null) || true
    UPDATED_LEARNED=$(echo "$GENOME_RAW" | jq -r 'if type == "array" then .[-1].result // empty else .result // . end' 2>/dev/null) || true

    if [ -n "$UPDATED_LEARNED" ] && [ ${#UPDATED_LEARNED} -gt 50 ] && echo "$UPDATED_LEARNED" | grep -q "Learned Patterns"; then
      echo "$UPDATED_LEARNED" > "$LEARNED_FILE"

      log_soul_event "genome_compaction" \
        --argjson old_size "$LEARNED_SIZE" \
        --argjson new_size "${#UPDATED_LEARNED}"
    else
      log_soul_event "genome_compaction" \
        --arg error "Genome compaction returned invalid result"
    fi
  fi
fi

# --- Auto-stage if enabled (stage only, never commit) ---
if [ "$AUTO_COMMIT" = "true" ] && command -v git &>/dev/null; then
  if git -C "$CWD" rev-parse --git-dir &>/dev/null 2>&1; then
    git -C "$CWD" add "$SOUL_FILE" 2>/dev/null || true
    if [ -d "${SOUL_DIR}/exports" ]; then
      git -C "$CWD" add "${SOUL_DIR}/exports" 2>/dev/null || true
    fi
    NOTIFY_MSG="${NOTIFY_MSG}. Changes staged — commit when you're ready"
  fi
fi

# --- Relay notification to next Stop hook invocation ---
# NOTE: Known race — if no Stop hook fires after this PostCompact (e.g., session ends
# immediately), the notification is lost. This is best-effort by design; PostCompact
# stdout is discarded by Claude Code, so temp file relay is the only option.
echo "$NOTIFY_MSG" > "/tmp/.soul-compact-notify-${SESSION_ID}"

exit 0
