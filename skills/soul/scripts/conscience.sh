#!/usr/bin/env bash
# SOUL Framework — Conscience + Pattern Extraction (Stop Hook)
# Three independent phases:
#   Phase 1: Tiered conscience audit (invariant checking with transcript context)
#   Phase 2: Three-tier correction detection (stop words -> Haiku micro-prompt -> extraction flag)
#   Phase 3: Pattern extraction from transcript (correction/feedback detection)
# Input: JSON on stdin with last_assistant_message, stop_hook_active, session_id, transcript_path
# Output: JSON with decision/systemMessage, or exit 0 to allow silently

set -euo pipefail

INPUT=$(cat)
CWD=$(echo "$INPUT" | jq -r '.cwd // ""')
SOUL_DIR="${CWD}/.soul"
SOUL_FILE="${SOUL_DIR}/SOUL.md"
CONFIG_FILE="${SOUL_DIR}/config.json"
LOG_DIR="${SOUL_DIR}/log"
GLOBAL_GENOME_DIR="${HOME}/.soul/genome"

# Bail if no .soul directory
if [ ! -d "$SOUL_DIR" ]; then
  exit 0
fi

# --- Source shared library (with fallback stubs if missing) ---
if [ -f "${SOUL_DIR}/hooks/lib.sh" ]; then
  source "${SOUL_DIR}/hooks/lib.sh"
fi
if ! type map_model_id &>/dev/null; then
  map_model_id() { echo "claude-haiku-4-5-20251001"; }
  log_soul_event() { :; }
fi

# --- Guard: prevent infinite loops ---
STOP_HOOK_ACTIVE=$(echo "$INPUT" | jq -r '.stop_hook_active // false')
if [ "$STOP_HOOK_ACTIVE" = "true" ]; then
  exit 0
fi

# --- Read config (validate JSON first to avoid set -e crash) ---
if [ -f "$CONFIG_FILE" ] && jq empty "$CONFIG_FILE" 2>/dev/null; then
  AUDIT_MODEL=$(jq -r '.conscience.auditModel // .conscience.model // "sonnet"' "$CONFIG_FILE")
  CORRECTION_MODEL=$(jq -r '.conscience.correctionModel // .conscience.model // "sonnet"' "$CONFIG_FILE")
  AUDIT_EVERY_N=$(jq -r '.conscience.auditEveryNTurns // 5' "$CONFIG_FILE")
  KILL_AFTER_N=$(jq -r '.conscience.killAfterNViolations // 3' "$CONFIG_FILE")
  AUDIT_KEYWORDS_JSON=$(jq -r '.conscience.alwaysAuditKeywords // ["commit","delete","deploy","push"]' "$CONFIG_FILE")
  CONTEXT_TURNS=$(jq -r '.conscience.contextTurns // 10' "$CONFIG_FILE")
  CORRECTION_DETECTION=$(jq -r '.conscience.correctionDetection // true' "$CONFIG_FILE")
  CORRECTION_KEYWORDS_JSON=$(jq -r '.conscience.correctionKeywords // ["no","don'\''t","stop","instead","wrong","not what I"]' "$CONFIG_FILE")
  PATTERN_MODEL=$(jq -r '.patterns.model // "sonnet"' "$CONFIG_FILE")
  EXTRACT_EVERY_K=$(jq -r '.patterns.extractEveryKTokens // 20' "$CONFIG_FILE")
  PROMOTE_CROSS_PROJECT=$(jq -r '.patterns.promoteToCrossProject // true' "$CONFIG_FILE")
  AUTO_WRITE_INVARIANTS=$(jq -r '.patterns.autoWriteInvariants // true' "$CONFIG_FILE")
else
  AUDIT_MODEL="sonnet"
  CORRECTION_MODEL="sonnet"
  AUDIT_EVERY_N=5
  KILL_AFTER_N=3
  AUDIT_KEYWORDS_JSON='["commit","delete","deploy","push"]'
  CONTEXT_TURNS=10
  CORRECTION_DETECTION="true"
  CORRECTION_KEYWORDS_JSON='["no","don'\''t","stop","instead","wrong","not what I"]'
  PATTERN_MODEL="sonnet"
  EXTRACT_EVERY_K=20
  PROMOTE_CROSS_PROJECT="true"
  AUTO_WRITE_INVARIANTS="true"
fi

AUDIT_MODEL_ID=$(map_model_id "$AUDIT_MODEL")
CORRECTION_MODEL_ID=$(map_model_id "$CORRECTION_MODEL")
PATTERN_MODEL_ID=$(map_model_id "$PATTERN_MODEL")

# --- Read last assistant message ---
LAST_MESSAGE=$(echo "$INPUT" | jq -r '.last_assistant_message // ""')
if [ -z "$LAST_MESSAGE" ]; then
  exit 0
fi

# --- Session tracking ---
SESSION_ID=$(echo "$INPUT" | jq -r '.session_id // "unknown"')
TRANSCRIPT_PATH=$(echo "$INPUT" | jq -r '.transcript_path // ""')
TURN_FILE="/tmp/.soul-turns-${SESSION_ID}"
VIOLATION_FILE="/tmp/.soul-violations-${SESSION_ID}"
EXTRACT_OFFSET_FILE="/tmp/.soul-extract-offset-${SESSION_ID}"
CORRECTION_FLAG_FILE="/tmp/.soul-correction-flag-${SESSION_ID}"

# Increment turn counter
if [ -f "$TURN_FILE" ]; then
  TURN_COUNT=$(cat "$TURN_FILE")
  TURN_COUNT=$((TURN_COUNT + 1))
else
  TURN_COUNT=1
fi
echo "$TURN_COUNT" > "$TURN_FILE"

# Initialize violation counter
if [ ! -f "$VIOLATION_FILE" ]; then
  echo "0" > "$VIOLATION_FILE"
fi
VIOLATION_COUNT=$(cat "$VIOLATION_FILE")

# --- Extract recent transcript context for audit ---
extract_recent_turns() {
  if [ -z "$TRANSCRIPT_PATH" ] || [ ! -f "$TRANSCRIPT_PATH" ]; then
    echo ""
    return
  fi

  # Read last N*3 lines from JSONL (each turn may span multiple lines, overshoot then truncate)
  local n_lines=$((CONTEXT_TURNS * 3))
  tail -n "$n_lines" "$TRANSCRIPT_PATH" | jq -r '
    select(.type == "human" or .type == "assistant")
    | if .type == "human" then
        "USER: " + (.message.content // "" | if type == "array" then map(select(.type == "text") | .text) | join(" ") else . end)
      elif .type == "assistant" then
        "ASSISTANT: " + (.message.content // [] | map(select(.type == "text") | .text) | join(" "))
      else empty end
  ' 2>/dev/null | tail -n "$CONTEXT_TURNS" | head -c 20000
}

# Store conscience decision for later (default: allow)
CONSCIENCE_DECISION=""
# Notification messages (concatenated with "; ")
NOTIFICATION_MSG=""
# Track if patterns were applied this turn
PATTERNS_APPLIED=false
NOTIFICATION_PATTERNS=0

# ============================================================
# PHASE 1: CONSCIENCE AUDIT
# ============================================================

RUN_FULL_AUDIT=false

# Check if this is an Nth turn
if [ $((TURN_COUNT % AUDIT_EVERY_N)) -eq 0 ]; then
  RUN_FULL_AUDIT=true
fi

# Check for significant keywords (always audit these)
LAST_MESSAGE_LOWER=$(echo "$LAST_MESSAGE" | tr '[:upper:]' '[:lower:]')
KEYWORD_MATCH=$(echo "$AUDIT_KEYWORDS_JSON" | jq -r '.[]' 2>/dev/null | while IFS= read -r keyword; do
  if echo "$LAST_MESSAGE_LOWER" | grep -qi "$keyword" 2>/dev/null; then
    echo "true"
    break
  fi
done)

if [ "$KEYWORD_MATCH" = "true" ]; then
  RUN_FULL_AUDIT=true
fi

if [ "$RUN_FULL_AUDIT" = "true" ]; then
  # Collect invariants
  INVARIANTS=""
  if [ -d "${SOUL_DIR}/invariants" ]; then
    for inv_file in "${SOUL_DIR}/invariants"/*.md; do
      if [ -f "$inv_file" ]; then
        name=$(basename "$inv_file" .md)
        INVARIANTS+="
## ${name}
$(cat "$inv_file")
"
      fi
    done
  fi

  if [ -n "$INVARIANTS" ]; then
    # Get transcript context for the audit
    RECENT_CONTEXT=$(extract_recent_turns)

    AUDIT_PROMPT="You are a conscience audit system. Your job is to check whether an AI agent's last response violates any of the following invariants.

INVARIANTS:
${INVARIANTS}

RECENT CONVERSATION CONTEXT (last ~${CONTEXT_TURNS} turns):
${RECENT_CONTEXT}

AGENT'S LAST RESPONSE (the one being audited):
${LAST_MESSAGE}

Analyze the agent's response IN CONTEXT of the conversation. Consider the user's requests and the agent's actions together. Does it violate ANY of the invariants listed above?

Reply with ONLY valid JSON (no markdown fencing, no explanation):
{\"violated\": true/false, \"invariant\": \"which invariant was violated (or null)\", \"reason\": \"brief explanation (or null)\"}

If no violation is detected, reply: {\"violated\": false, \"invariant\": null, \"reason\": null}"

    RAW_OUTPUT=$(echo "$AUDIT_PROMPT" | timeout 30 claude -p --model "$AUDIT_MODEL_ID" --output-format json 2>/dev/null) || true

    if [ -z "$RAW_OUTPUT" ]; then
      log_soul_event "audit_timeout" --argjson turn "$TURN_COUNT"
    fi

    AUDIT_RESULT=$(echo "$RAW_OUTPUT" | jq -r 'if type == "array" then .[-1].result // empty else .result // . end' 2>/dev/null) || true

    # Strip markdown code fencing if present (delete fence lines, preserve content newlines)
    AUDIT_RESULT=$(echo "$AUDIT_RESULT" | sed '/^```/d')

    VIOLATED=$(echo "$AUDIT_RESULT" | jq -r '.violated // false' 2>/dev/null) || VIOLATED="false"

    if [ "$VIOLATED" = "true" ]; then
      REASON=$(echo "$AUDIT_RESULT" | jq -r '.reason // "Invariant violation detected"' 2>/dev/null) || REASON="Invariant violation detected"
      INVARIANT=$(echo "$AUDIT_RESULT" | jq -r '.invariant // "unknown"' 2>/dev/null) || INVARIANT="unknown"

      VIOLATION_COUNT=$((VIOLATION_COUNT + 1))
      echo "$VIOLATION_COUNT" > "$VIOLATION_FILE"

      log_soul_event "audit_violation" \
        --argjson turn "$TURN_COUNT" \
        --arg invariant "$INVARIANT" \
        --arg reason "$REASON" \
        --argjson violation_count "$VIOLATION_COUNT"

      if [ "$VIOLATION_COUNT" -ge "$KILL_AFTER_N" ]; then
        CONSCIENCE_DECISION=$(jq -n \
          --arg reason "SOUL: Rule violated ${VIOLATION_COUNT} times (${INVARIANT}: ${REASON}). Please restart the session so I can reset." \
          '{decision: "block", reason: $reason}')
      else
        CONSCIENCE_DECISION=$(jq -n \
          --arg reason "SOUL: Rule violation (${VIOLATION_COUNT}/${KILL_AFTER_N}). ${INVARIANT}: ${REASON}. Please fix this before continuing." \
          '{decision: "block", reason: $reason}')
      fi
    else
      # Log audit pass
      log_soul_event "audit_pass" \
        --argjson turn "$TURN_COUNT" \
        --argjson keyword_triggered "${KEYWORD_MATCH:-false}" \
        --argjson context_turns_sent "$CONTEXT_TURNS"

      # Notify on keyword-triggered audits that passed
      if [ "$KEYWORD_MATCH" = "true" ]; then
        NOTIFICATION_MSG="All good"
      fi
    fi
  fi
fi

# ============================================================
# PHASE 2: THREE-TIER CORRECTION DETECTION
# Tier 1: Stop word scan (free, every turn)
# Tier 2: Haiku micro-prompt (cheap, only when Tier 1 matches)
# Tier 3: Flag for extraction threshold reduction
# ============================================================

CORRECTION_DETECTED=false

if [ "$CORRECTION_DETECTION" = "true" ] && [ -n "$TRANSCRIPT_PATH" ] && [ -f "$TRANSCRIPT_PATH" ]; then
  # Tier 1: Check last 3 human messages for correction stop words
  RECENT_USER_MSGS=$(tail -10 "$TRANSCRIPT_PATH" | jq -r '
    select(.type == "human")
    | .message.content // "" | if type == "array" then map(select(.type == "text") | .text) | join(" ") else . end
  ' 2>/dev/null | tail -3) || true

  TIER1_MATCH=false
  if [ -n "$RECENT_USER_MSGS" ]; then
    RECENT_USER_LOWER=$(echo "$RECENT_USER_MSGS" | tr '[:upper:]' '[:lower:]')
    TIER1_MATCH=$(echo "$CORRECTION_KEYWORDS_JSON" | jq -r '.[]' 2>/dev/null | while IFS= read -r kw; do
      if echo "$RECENT_USER_LOWER" | grep -qi "$kw" 2>/dev/null; then
        echo "true"
        break
      fi
    done)
    TIER1_MATCH="${TIER1_MATCH:-false}"
  fi

  if [ "$TIER1_MATCH" = "true" ]; then
    # Get the most recent user message for Tier 2
    LATEST_USER_MSG=$(echo "$RECENT_USER_MSGS" | tail -1 | head -c 500)

    log_soul_event "correction_tier1" \
      --argjson turn "$TURN_COUNT" \
      --argjson keyword_matched true \
      --arg user_msg_snippet "$(echo "$LATEST_USER_MSG" | head -c 100)"

    # Tier 2: Haiku micro-prompt to confirm it's actually a correction
    CORRECTION_PROMPT="Is this user message correcting or criticizing the agent's behavior? Reply ONLY \"YES\" or \"NO\".

USER MESSAGE:
${LATEST_USER_MSG}"

    TIER2_RAW=$(echo "$CORRECTION_PROMPT" | timeout 15 claude -p --model "$CORRECTION_MODEL_ID" --output-format json 2>/dev/null) || true
    TIER2_RESULT=$(echo "$TIER2_RAW" | jq -r 'if type == "array" then .[-1].result // empty else .result // . end' 2>/dev/null) || true
    TIER2_VERDICT=$(echo "$TIER2_RESULT" | tr '[:lower:]' '[:upper:]' | grep -o 'YES\|NO' | head -1)

    log_soul_event "correction_tier2" \
      --argjson turn "$TURN_COUNT" \
      --arg haiku_verdict "${TIER2_VERDICT:-UNKNOWN}"

    # Tier 3: If confirmed correction, flag for extraction
    if [ "$TIER2_VERDICT" = "YES" ]; then
      CORRECTION_DETECTED=true
      touch "$CORRECTION_FLAG_FILE"
      NOTIFICATION_MSG="${NOTIFICATION_MSG:+${NOTIFICATION_MSG}; }Noticed a correction, will learn from it soon"
    fi
  fi
fi

# ============================================================
# PHASE 3: PATTERN EXTRACTION
# Runs independently of conscience audit, on its own schedule.
# Trigger: transcript growth >= threshold (lowered 5x if correction flagged).
# ============================================================

run_pattern_extraction() {
  # Check if transcript exists
  if [ -z "$TRANSCRIPT_PATH" ] || [ ! -f "$TRANSCRIPT_PATH" ]; then
    return
  fi

  # Check if SOUL.md exists
  if [ ! -f "$SOUL_FILE" ]; then
    return
  fi

  # Get current transcript size
  TRANSCRIPT_SIZE=$(wc -c < "$TRANSCRIPT_PATH" 2>/dev/null) || return
  TRANSCRIPT_SIZE=$(echo "$TRANSCRIPT_SIZE" | tr -d ' ')

  # Get last extraction offset
  if [ -f "$EXTRACT_OFFSET_FILE" ]; then
    LAST_OFFSET=$(cat "$EXTRACT_OFFSET_FILE")
  else
    LAST_OFFSET=0
  fi

  # Calculate growth (~4 chars/token, extractEveryKTokens * 1000 * 4 = bytes threshold)
  THRESHOLD_BYTES=$((EXTRACT_EVERY_K * 1000 * 4))
  GROWTH=$((TRANSCRIPT_SIZE - LAST_OFFSET))

  # Correction-triggered extraction: lower threshold 5x if flagged
  CORRECTION_FLAGGED=false
  if [ -f "$CORRECTION_FLAG_FILE" ]; then
    THRESHOLD_BYTES=$((THRESHOLD_BYTES / 5))
    CORRECTION_FLAGGED=true
  fi

  if [ "$GROWTH" -lt "$THRESHOLD_BYTES" ]; then
    # Don't consume the correction flag — preserve it for the next call
    # so the correction isn't permanently lost on short transcripts
    return
  fi

  # Threshold passed — safe to consume the flag now
  if [ "$CORRECTION_FLAGGED" = "true" ]; then
    rm -f "$CORRECTION_FLAG_FILE"
  fi

  # --- Extract transcript window ---
  # Read from last offset, capped at 200KB
  WINDOW_SIZE=200000
  if [ "$GROWTH" -lt "$WINDOW_SIZE" ]; then
    WINDOW_SIZE=$GROWTH
  fi

  # Use tail to get the recent portion of transcript, focus on user+assistant messages
  TRANSCRIPT_WINDOW=$(tail -c "$WINDOW_SIZE" "$TRANSCRIPT_PATH" | jq -r '
    select(.type == "human" or .type == "assistant")
    | if .type == "human" then
        "USER: " + (.message.content // "" | if type == "array" then map(select(.type == "text") | .text) | join(" ") else . end)
      elif .type == "assistant" then
        "ASSISTANT: " + (.message.content // [] | map(select(.type == "text") | .text) | join(" "))
      else empty end
  ' 2>/dev/null | head -c 100000) || true

  if [ -z "$TRANSCRIPT_WINDOW" ]; then
    return
  fi

  # --- Read current SOUL.md ---
  CURRENT_SOUL=$(cat "$SOUL_FILE")

  # --- Read current genome ---
  CURRENT_GENOME=""
  if [ -d "$GLOBAL_GENOME_DIR" ]; then
    for genome_file in "$GLOBAL_GENOME_DIR"/*.md; do
      if [ -f "$genome_file" ]; then
        name=$(basename "$genome_file" .md)
        CURRENT_GENOME+="
--- ${name} ---
$(cat "$genome_file")
"
      fi
    done
  fi

  # --- Read current invariants ---
  CURRENT_INVARIANTS=""
  if [ -d "${SOUL_DIR}/invariants" ]; then
    for inv_file in "${SOUL_DIR}/invariants"/*.md; do
      if [ -f "$inv_file" ]; then
        name=$(basename "$inv_file" .md)
        CURRENT_INVARIANTS+="
--- ${name} ---
$(cat "$inv_file")
"
      fi
    done
  fi

  # --- Build extraction prompt ---
  EXTRACT_PROMPT="You are a pattern extraction system for the SOUL framework. Read this conversation transcript and identify three types of patterns:

1. USER CORRECTIONS — the user told the agent to stop doing something or do something differently
   Examples: \"no, don't do that\", \"stop using X\", \"use Y instead\", \"that's wrong\"

2. CONFIRMED APPROACHES — the user validated a non-obvious choice the agent made
   Examples: \"yes, exactly\", \"perfect, keep doing that\", \"good call\", user building on the agent's suggestion, user accepting an unusual approach without pushback (e.g., agent chose to bundle into one PR and user said \"yeah that was the right call\")
   NOT confirmations: routine \"ok\", \"thanks\", \"got it\" — these are acknowledgments, not pattern signals

3. BEHAVIORAL PREFERENCES — how the user wants to work (tone, process, style, communication)
   Examples: \"don't summarize at the end\", \"keep responses shorter\", \"always ask before deleting\"

Classify each pattern's scope:
- \"repo\" — specific to this project (e.g., \"use Knex not raw SQL in this codebase\")
- \"cross-project\" — applies everywhere (e.g., \"user prefers concise responses\")

IMPORTANT — FACTS vs ACTIONS classification:
- A FACT is something that IS TRUE: \"LuaJIT uses Lua 5.1 syntax\", \"the API uses REST not GraphQL\", \"Redis is the caching layer\"
- An ACTION is a behavioral rule telling the agent what to DO or NOT DO: \"never use subagents\", \"always ask before deleting\", \"don't summarize\"
- soul_updates.accumulated_knowledge accepts ONLY facts — things about the project, tools, or codebase that are true
- Actions (behavioral rules) go in invariant_suggestions instead — they need human review to become enforced invariants

CURRENT SOUL.MD:
${CURRENT_SOUL}

CURRENT GENOME:
${CURRENT_GENOME}

CURRENT INVARIANTS (human-authored, immutable — stored in .soul/invariants/):
${CURRENT_INVARIANTS}

RECENT TRANSCRIPT:
${TRANSCRIPT_WINDOW}

Output ONLY valid JSON (no markdown fencing):
{
  \"patterns\": [
    {
      \"type\": \"correction|confirmation|preference\",
      \"scope\": \"repo|cross-project\",
      \"summary\": \"one-line description\",
      \"detail\": \"for corrections: what to do differently and why. for confirmations: what worked well and why it should be repeated. for preferences: the specific preference and when it applies\",
      \"source\": \"brief quote from transcript\"
    }
  ],
  \"soul_updates\": {
    \"accumulated_knowledge\": [\"FACTS ONLY — things that are true about the project, tools, or codebase. NO behavioral rules or DO/DON'T instructions.\"]
  },
  \"invariant_suggestions\": [
    {
      \"rule\": \"concise behavioral rule (e.g., 'Never use subagents for implementation tasks')\",
      \"evidence\": \"why this qualifies — quote the user's words\",
      \"suggested_file\": \"behavior.md or architecture.md or a new descriptive filename\"
    }
  ],
  \"tool_rules\": [
    {
      \"block_tool\": \"exact Claude Code tool name to block (Agent, Bash, Edit, Read, Write, Glob, Grep) — use for invariants that prohibit a specific tool\",
      \"reason\": \"human-readable reason\",
      \"source\": \"learned.md\"
    },
    {
      \"block_bash_pattern\": \"regex pattern to match against bash commands (e.g., 'git push.*(--force|-f)')\",
      \"reason\": \"human-readable reason\",
      \"source\": \"learned.md\"
    }
  ],
  \"genome_updates\": [\"new lines for ~/.soul/genome/learned.md\"]
}

Rules:
- Only extract patterns the user explicitly expressed or clearly demonstrated
- Do not infer preferences from silence — routine acknowledgments are not confirmations
- A confirmation requires the user to validate a choice that was non-obvious or non-default
- If no patterns found, return empty arrays for all fields
- Cross-project patterns must be genuinely universal, not project-specific
- Do not duplicate patterns already present in SOUL.md or the genome
- Do not write to soul_updates anything already covered by the INVARIANTS above
- accumulated_knowledge is for FACTS only — if a pattern is an action/rule, put it in invariant_suggestions instead
- invariant_suggestions should contain behavioral rules, especially those with absolute language (never/always) or user frustration/repetition
- For each invariant_suggestion that blocks a specific tool or bash pattern, also emit a corresponding tool_rules entry for machine-readable enforcement
- tool_rules block_tool must be an exact Claude Code tool name: Agent, Bash, Edit, Read, Write, Glob, Grep
- tool_rules block_bash_pattern must be a valid regex pattern
- Balance corrections and confirmations — both are valuable signals"

  # --- Run extraction via claude -p ---
  RAW_OUTPUT=$(echo "$EXTRACT_PROMPT" | timeout 60 claude -p --model "$PATTERN_MODEL_ID" --output-format json 2>/dev/null) || true
  EXTRACT_RESULT=$(echo "$RAW_OUTPUT" | jq -r 'if type == "array" then .[-1].result // empty else .result // . end' 2>/dev/null) || true

  # Strip markdown code fencing (delete fence lines, preserve content newlines)
  EXTRACT_RESULT=$(echo "$EXTRACT_RESULT" | sed '/^```/d')

  # Validate JSON
  if ! echo "$EXTRACT_RESULT" | jq empty 2>/dev/null; then
    log_soul_event "extraction_failed" \
      --argjson turn "$TURN_COUNT" \
      --arg error "Pattern extraction returned invalid JSON"
    # Still update offset so we don't retry the same window
    echo "$TRANSCRIPT_SIZE" > "$EXTRACT_OFFSET_FILE"
    return
  fi

  # --- Count patterns found ---
  PATTERN_COUNT=$(echo "$EXTRACT_RESULT" | jq '.patterns | length' 2>/dev/null) || PATTERN_COUNT=0

  if [ "$PATTERN_COUNT" -eq 0 ]; then
    # No patterns found — update offset and move on
    echo "$TRANSCRIPT_SIZE" > "$EXTRACT_OFFSET_FILE"
    log_soul_event "extraction" \
      --argjson turn "$TURN_COUNT" \
      --argjson pattern_count 0 \
      --arg model "$PATTERN_MODEL_ID"
    return
  fi

  # --- Apply soul_updates to SOUL.md ---
  SOUL_MODIFIED=false

  # Append to Accumulated Knowledge
  AK_UPDATES=$(echo "$EXTRACT_RESULT" | jq -r '.soul_updates.accumulated_knowledge // [] | .[]' 2>/dev/null) || true
  if [ -n "$AK_UPDATES" ]; then
    while IFS= read -r bullet; do
      if [ -n "$bullet" ]; then
        if grep -q "^## Accumulated Knowledge" "$SOUL_FILE"; then
          AK_LINE=$(grep -n "^## Accumulated Knowledge" "$SOUL_FILE" | head -1 | cut -d: -f1)
          NEXT_SECTION=$(tail -n +"$((AK_LINE + 1))" "$SOUL_FILE" | grep -n "^## " | head -1 | cut -d: -f1)
          if [ -n "$NEXT_SECTION" ]; then
            INSERT_LINE=$((AK_LINE + NEXT_SECTION))
            sed "${INSERT_LINE}i\\
- ${bullet}
" "$SOUL_FILE" > "${SOUL_FILE}.tmp" && mv "${SOUL_FILE}.tmp" "$SOUL_FILE"
          else
            echo "- ${bullet}" >> "$SOUL_FILE"
          fi
          SOUL_MODIFIED=true
        fi
      fi
    done <<< "$AK_UPDATES"
  fi

  # --- Process invariant_suggestions → auto-write or notify ---
  INV_SUGGESTIONS=$(echo "$EXTRACT_RESULT" | jq -r '.invariant_suggestions // [] | length' 2>/dev/null) || INV_SUGGESTIONS=0
  if [ "$INV_SUGGESTIONS" -gt 0 ]; then
    LEARNED_INV_FILE="${SOUL_DIR}/invariants/learned.md"
    RULES_WRITTEN=0

    if [ "$AUTO_WRITE_INVARIANTS" = "true" ]; then
      # Initialize learned.md if missing
      if [ ! -f "$LEARNED_INV_FILE" ]; then
        mkdir -p "${SOUL_DIR}/invariants"
        cat > "$LEARNED_INV_FILE" << 'LEARNED_INV_EOF'
# Learned Invariants

<!-- Auto-generated from user corrections. Edit or delete rules that are wrong. -->
LEARNED_INV_EOF
      fi

      # Write each suggestion, deduplicating
      while IFS= read -r suggestion; do
        rule=$(echo "$suggestion" | jq -r '.rule')
        if [ -n "$rule" ] && ! grep -qiF "$rule" "$LEARNED_INV_FILE" 2>/dev/null; then
          echo "- ${rule}" >> "$LEARNED_INV_FILE"
          RULES_WRITTEN=$((RULES_WRITTEN + 1))
        fi
      done < <(echo "$EXTRACT_RESULT" | jq -c '.invariant_suggestions // [] | .[]' 2>/dev/null)

      # Also write tool_rules if extraction produced them
      TOOL_RULES=$(echo "$EXTRACT_RESULT" | jq -c '.tool_rules // []' 2>/dev/null)
      if [ "$TOOL_RULES" != "[]" ] && [ "$TOOL_RULES" != "null" ]; then
        TOOL_RULES_FILE="${SOUL_DIR}/invariants/tool-rules.json"
        if [ -f "$TOOL_RULES_FILE" ]; then
          MERGED=$(jq -s '.[0] + .[1] | unique_by(.block_tool // .block_bash_pattern // .require_prior_read)' \
            "$TOOL_RULES_FILE" <(echo "$TOOL_RULES"))
          echo "$MERGED" > "$TOOL_RULES_FILE"
        else
          echo "$TOOL_RULES" | jq '.' > "$TOOL_RULES_FILE"
        fi
      fi

      if [ "$RULES_WRITTEN" -gt 0 ]; then
        NOTIFICATION_MSG="${NOTIFICATION_MSG:+${NOTIFICATION_MSG}; }Learned ${RULES_WRITTEN} rule(s) — enforced immediately. Edit .soul/invariants/learned.md to change"
        SOUL_MODIFIED=true
      fi

      log_soul_event "invariant_auto_written" \
        --argjson turn "$TURN_COUNT" \
        --argjson count "$RULES_WRITTEN" \
        --argjson suggestions "$(echo "$EXTRACT_RESULT" | jq -c '.invariant_suggestions // []')"
    else
      # Suggest-only mode (original behavior)
      INV_RULES=$(echo "$EXTRACT_RESULT" | jq -r '.invariant_suggestions[] | "\"" + .rule + "\" → " + .suggested_file' 2>/dev/null) || true
      if [ -n "$INV_RULES" ]; then
        NOTIFICATION_MSG="${NOTIFICATION_MSG:+${NOTIFICATION_MSG}; }Suggested invariant(s): ${INV_RULES}. Add to .soul/invariants/ to enforce"
      fi
      log_soul_event "invariant_suggestion" \
        --argjson turn "$TURN_COUNT" \
        --argjson count "$INV_SUGGESTIONS" \
        --argjson suggestions "$(echo "$EXTRACT_RESULT" | jq -c '.invariant_suggestions // []')"
    fi
  fi

  # --- Apply genome_updates to ~/.soul/genome/learned.md ---
  if [ "$PROMOTE_CROSS_PROJECT" = "true" ]; then
    GENOME_UPDATES=$(echo "$EXTRACT_RESULT" | jq -r '.genome_updates // [] | .[]' 2>/dev/null) || true
    if [ -n "$GENOME_UPDATES" ]; then
      mkdir -p "$GLOBAL_GENOME_DIR"
      LEARNED_FILE="${GLOBAL_GENOME_DIR}/learned.md"

      if [ ! -f "$LEARNED_FILE" ]; then
        cat > "$LEARNED_FILE" << 'LEARNED_INIT'
# Learned Patterns

Cross-project patterns extracted from sessions by SOUL's pattern detection system.
These are automatically detected from user corrections and confirmed behaviors.

## Patterns
LEARNED_INIT
      fi

      while IFS= read -r line; do
        if [ -n "$line" ]; then
          echo "- ${line}" >> "$LEARNED_FILE"
        fi
      done <<< "$GENOME_UPDATES"
    fi
  fi

  # --- Update extraction offset ---
  echo "$TRANSCRIPT_SIZE" > "$EXTRACT_OFFSET_FILE"

  # --- Log extraction to unified activity log ---
  PATTERN_SUMMARIES=$(echo "$EXTRACT_RESULT" | jq -c '[.patterns[].summary]' 2>/dev/null) || PATTERN_SUMMARIES="[]"
  INV_SUGGESTION_COUNT=$(echo "$EXTRACT_RESULT" | jq '.invariant_suggestions // [] | length' 2>/dev/null) || INV_SUGGESTION_COUNT=0
  log_soul_event "extraction" \
    --argjson turn "$TURN_COUNT" \
    --argjson pattern_count "$PATTERN_COUNT" \
    --argjson patterns "$PATTERN_SUMMARIES" \
    --argjson soul_modified "$SOUL_MODIFIED" \
    --argjson invariant_suggestions "$INV_SUGGESTION_COUNT" \
    --arg model "$PATTERN_MODEL_ID" \
    --argjson transcript_offset "$TRANSCRIPT_SIZE"

  # --- Log to recent-extractions.jsonl for /soul review ---
  STAGING_DIR="${SOUL_DIR}/staging"
  mkdir -p "$STAGING_DIR"
  echo "$EXTRACT_RESULT" | jq -c \
    --arg ts "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    --arg session "$SESSION_ID" \
    --argjson turn "$TURN_COUNT" \
    '{extracted_at: $ts, session: $session, turn: ($turn | tonumber)} + .' \
    >> "${STAGING_DIR}/recent-extractions.jsonl" 2>/dev/null || true

  # --- Set notification variables ---
  PATTERNS_APPLIED=true
  NOTIFICATION_PATTERNS=$PATTERN_COUNT

  # --- Auto-export skills if SOUL.md was modified with new knowledge ---
  if [ "$SOUL_MODIFIED" = "true" ]; then
    EXPORT_SCRIPT=""
    if [ -x "${SOUL_DIR}/hooks/export-skill.sh" ]; then
      EXPORT_SCRIPT="${SOUL_DIR}/hooks/export-skill.sh"
    elif [ -x "${CWD}/skills/soul/scripts/export-skill.sh" ]; then
      EXPORT_SCRIPT="${CWD}/skills/soul/scripts/export-skill.sh"
    fi

    if [ -n "$EXPORT_SCRIPT" ]; then
      skill_names=$(sed -n '/^## Skills$/,/^## /{ /^### /{s/^### *//;p;} }' "$SOUL_FILE")
      if [ -n "$skill_names" ]; then
        while IFS= read -r sname; do
          if [ -n "$sname" ]; then
            CWD="$CWD" "$EXPORT_SCRIPT" "$sname" --quiet 2>/dev/null || true
            log_soul_event "skill_export" \
              --arg skill_name "$sname" \
              --arg trigger "extraction"
          fi
        done <<< "$skill_names"
      fi
    fi
  fi
}

# Run pattern extraction (independent of conscience audit)
run_pattern_extraction

# ============================================================
# OUTPUT: Return conscience decision or informational notification
# ============================================================

# Check for pending compaction notification (relayed from compact.sh via temp file)
COMPACT_NOTIFY_FILE="/tmp/.soul-compact-notify-${SESSION_ID}"
if [ -f "$COMPACT_NOTIFY_FILE" ]; then
  COMPACT_MSG=$(cat "$COMPACT_NOTIFY_FILE")
  rm -f "$COMPACT_NOTIFY_FILE"
  NOTIFICATION_MSG="${NOTIFICATION_MSG:+${NOTIFICATION_MSG}; }${COMPACT_MSG}"
fi

# Add pattern notification
if [ "$PATTERNS_APPLIED" = "true" ] && [ "$NOTIFICATION_PATTERNS" -gt 0 ]; then
  NOTIFICATION_MSG="${NOTIFICATION_MSG:+${NOTIFICATION_MSG}; }Learned ${NOTIFICATION_PATTERNS} pattern(s) from this session. Run /soul review to see them"
fi

# Build output
if [ -n "$CONSCIENCE_DECISION" ]; then
  # Blocking violation — append any pending notifications so they aren't lost
  if [ -n "$NOTIFICATION_MSG" ]; then
    CONSCIENCE_DECISION=$(echo "$CONSCIENCE_DECISION" | jq --arg note " (Also: ${NOTIFICATION_MSG})" '.reason += $note')
  fi
  echo "$CONSCIENCE_DECISION"
elif [ -n "$NOTIFICATION_MSG" ]; then
  # Informational notification via systemMessage
  jq -n --arg msg "SOUL: ${NOTIFICATION_MSG}" '{systemMessage: $msg}'
else
  exit 0
fi
