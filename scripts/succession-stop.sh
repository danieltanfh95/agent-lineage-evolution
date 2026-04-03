#!/usr/bin/env bash
# Succession — Stop Hook
# Three phases:
#   1. Three-tier correction detection (free → Sonnet micro-prompt → flag)
#   2. Pattern extraction → individual rule files
#   3. Advisory rule re-injection via additionalContext
#
# Input: JSON on stdin with session_id, transcript_path, cwd, etc.
# Output: JSON with decision/reason/additionalContext

set -euo pipefail

INPUT=$(cat)
CWD=$(echo "$INPUT" | jq -r '.cwd // ""')
SESSION_ID=$(echo "$INPUT" | jq -r '.session_id // "unknown"')
TRANSCRIPT_PATH=$(echo "$INPUT" | jq -r '.transcript_path // ""')

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/lib.sh"

# Directories
SUCCESSION_PROJECT_DIR="${CWD}/.succession"
COMPILED_DIR="${SUCCESSION_PROJECT_DIR}/compiled"
RULES_DIR="${SUCCESSION_PROJECT_DIR}/rules"
LOG_DIR="${SUCCESSION_PROJECT_DIR}/log"
CONFIG_FILE="${SUCCESSION_GLOBAL_DIR}/config.json"

# Config defaults
EXTRACTION_MODEL="sonnet"
CORRECTION_MODEL="sonnet"
EXTRACT_EVERY_K_TOKENS=80000  # ~80KB of transcript growth
REINJECTION_INTERVAL=10       # Re-inject advisory rules every N turns
CORRECTION_KEYWORDS_JSON='["no,","not what","don'\''t","dont","stop ","instead","wrong","that'\''s not","thats not","I said","i said","actually,","please don'\''t","undo","revert","go back"]'

# Load config if present
if [ -f "$CONFIG_FILE" ] && jq empty "$CONFIG_FILE" 2>/dev/null; then
  EXTRACTION_MODEL=$(jq -r '.model // "sonnet"' "$CONFIG_FILE")
  CORRECTION_MODEL=$(jq -r '.correctionModel // "sonnet"' "$CONFIG_FILE")
  EXTRACT_EVERY_K_TOKENS=$(jq -r '.extractEveryKTokens // 80000' "$CONFIG_FILE")
  REINJECTION_INTERVAL=$(jq -r '.reinjectionInterval // 10' "$CONFIG_FILE")
fi

EXTRACTION_MODEL_ID=$(map_model_id "$EXTRACTION_MODEL")
CORRECTION_MODEL_ID=$(map_model_id "$CORRECTION_MODEL")

# State files
TURN_COUNT_FILE="/tmp/.succession-turns-${SESSION_ID}"
EXTRACT_OFFSET_FILE="/tmp/.succession-extract-offset-${SESSION_ID}"
CORRECTION_FLAG_FILE="/tmp/.succession-correction-flag-${SESSION_ID}"

# Initialize turn counter
if [ -f "$TURN_COUNT_FILE" ]; then
  TURN_COUNT=$(cat "$TURN_COUNT_FILE")
  TURN_COUNT=$((TURN_COUNT + 1))
else
  TURN_COUNT=1
fi
echo "$TURN_COUNT" > "$TURN_COUNT_FILE"

# Guard: bail if no transcript
if [ -z "$TRANSCRIPT_PATH" ] || [ ! -f "$TRANSCRIPT_PATH" ]; then
  exit 0
fi

mkdir -p "$RULES_DIR" "$COMPILED_DIR" "$LOG_DIR"

NOTIFICATION_MSG=""
OUTPUT_JSON='{}'

# ============================================================
# PHASE 1: THREE-TIER CORRECTION DETECTION
# Tier 1: Stop word scan (free, every turn)
# Tier 2: Sonnet micro-prompt (cheap, only when Tier 1 matches)
# Tier 3: Flag for extraction threshold reduction
# ============================================================

CORRECTION_DETECTED=false

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
  LATEST_USER_MSG=$(echo "$RECENT_USER_MSGS" | tail -1 | head -c 500)

  log_succession_event "correction_tier1" \
    --argjson turn "$TURN_COUNT" \
    --arg user_msg_snippet "$(echo "$LATEST_USER_MSG" | head -c 100)"

  # Tier 2: Sonnet micro-prompt to confirm it's actually a correction
  CORRECTION_PROMPT="Is this user message correcting or criticizing the agent's behavior? Reply ONLY \"YES\" or \"NO\".

USER MESSAGE:
${LATEST_USER_MSG}"

  TIER2_RAW=$(echo "$CORRECTION_PROMPT" | timeout 15 claude -p --model "$CORRECTION_MODEL_ID" --output-format json 2>/dev/null) || true
  TIER2_RESULT=$(echo "$TIER2_RAW" | jq -r 'if type == "array" then .[-1].result // empty else .result // . end' 2>/dev/null) || true
  TIER2_VERDICT=$(echo "$TIER2_RESULT" | tr '[:lower:]' '[:upper:]' | grep -o 'YES\|NO' | head -1)

  log_succession_event "correction_tier2" \
    --argjson turn "$TURN_COUNT" \
    --arg verdict "${TIER2_VERDICT:-UNKNOWN}"

  # Tier 3: If confirmed correction, check for existing rule match before flagging
  if [ "$TIER2_VERDICT" = "YES" ]; then
    CORRECTION_DETECTED=true

    # Semantic violation matching: check if this correction matches an existing rule
    EXISTING_RULE_IDS=""
    EXISTING_RULE_SUMMARIES=""
    for rf in "$RULES_DIR"/*.md "${SUCCESSION_GLOBAL_DIR}/rules"/*.md; do
      [ -f "$rf" ] || continue
      local_rid=$(basename "$rf" .md)
      local_rsummary=$(parse_rule_body "$rf" | head -1)
      EXISTING_RULE_IDS+="${local_rid} "
      EXISTING_RULE_SUMMARIES+="- ${local_rid}: ${local_rsummary}
"
    done

    MATCHED_RULE=""
    if [ -n "$EXISTING_RULE_SUMMARIES" ]; then
      MATCH_PROMPT="Given this user correction: '${LATEST_USER_MSG}'
And these existing rules:
${EXISTING_RULE_SUMMARIES}
Does this correction indicate a violation of an existing rule? If yes, return ONLY the rule ID. If no, return ONLY the word null."

      MATCH_RAW=$(echo "$MATCH_PROMPT" | timeout 15 claude -p --model "$CORRECTION_MODEL_ID" --output-format json 2>/dev/null) || true
      MATCH_RESULT=$(echo "$MATCH_RAW" | jq -r 'if type == "array" then .[-1].result // empty else .result // . end' 2>/dev/null) || true
      MATCH_RESULT=$(echo "$MATCH_RESULT" | tr -d '[:space:]' | tr '[:upper:]' '[:lower:]')

      if [ "$MATCH_RESULT" != "null" ] && [ -n "$MATCH_RESULT" ]; then
        # Check if the matched ID is actually a valid rule
        for rf in "$RULES_DIR"/*.md "${SUCCESSION_GLOBAL_DIR}/rules"/*.md; do
          [ -f "$rf" ] || continue
          if [ "$(basename "$rf" .md)" = "$MATCH_RESULT" ]; then
            MATCHED_RULE="$MATCH_RESULT"
            break
          fi
        done
      fi
    fi

    if [ -n "$MATCHED_RULE" ]; then
      # Existing rule was violated — log it, don't create a new rule
      log_meta_cognition_event "rule_violated" \
        --arg rule_id "$MATCHED_RULE" \
        --arg context "$LATEST_USER_MSG" \
        --arg detected_by "correction-matching"

      log_meta_cognition_event "correction_matched" \
        --arg rule_id "$MATCHED_RULE" \
        --arg user_msg "$(echo "$LATEST_USER_MSG" | head -c 200)"

      NOTIFICATION_MSG="Matched correction to existing rule: ${MATCHED_RULE}"
    else
      touch "$CORRECTION_FLAG_FILE"
      NOTIFICATION_MSG="Noticed a correction, will learn from it soon"
    fi
  fi
fi

# ============================================================
# PHASE 2: PATTERN EXTRACTION → INDIVIDUAL RULE FILES
# Runs when transcript has grown enough (or sooner if correction flagged)
# ============================================================

TRANSCRIPT_SIZE=$(wc -c < "$TRANSCRIPT_PATH" | tr -d ' ')
LAST_EXTRACT_OFFSET=0
if [ -f "$EXTRACT_OFFSET_FILE" ]; then
  LAST_EXTRACT_OFFSET=$(cat "$EXTRACT_OFFSET_FILE")
fi
TRANSCRIPT_GROWTH=$((TRANSCRIPT_SIZE - LAST_EXTRACT_OFFSET))

# Reduce threshold 5x if a correction was recently flagged
EFFECTIVE_THRESHOLD=$EXTRACT_EVERY_K_TOKENS
if [ -f "$CORRECTION_FLAG_FILE" ]; then
  EFFECTIVE_THRESHOLD=$((EXTRACT_EVERY_K_TOKENS / 5))
fi

if [ "$TRANSCRIPT_GROWTH" -ge "$EFFECTIVE_THRESHOLD" ]; then
  # Read transcript window (from last offset, capped at 200KB)
  WINDOW_START=$LAST_EXTRACT_OFFSET
  WINDOW_SIZE=$((TRANSCRIPT_SIZE - WINDOW_START))
  if [ "$WINDOW_SIZE" -gt 204800 ]; then
    WINDOW_START=$((TRANSCRIPT_SIZE - 204800))
  fi

  TRANSCRIPT_WINDOW=$(tail -c +"$((WINDOW_START + 1))" "$TRANSCRIPT_PATH" | head -c 204800 | jq -r '
    select(.type == "human" or .type == "assistant")
    | if .type == "human" then
        "USER: " + ((.message.content // "") | if type == "array" then map(select(.type == "text") | .text) | join(" ") else . end)
      else
        "ASSISTANT: " + ((.message.content // "") | if type == "array" then map(select(.type == "text") | .text) | join(" ") else . end)
      end
  ' 2>/dev/null | head -c 100000) || true

  if [ -z "$TRANSCRIPT_WINDOW" ]; then
    echo "$TRANSCRIPT_SIZE" > "$EXTRACT_OFFSET_FILE"
  else
    # Load existing rules for deduplication context
    EXISTING_RULES=""
    for rf in "$RULES_DIR"/*.md "${SUCCESSION_GLOBAL_DIR}/rules"/*.md; do
      [ -f "$rf" ] || continue
      EXISTING_RULES+="- $(basename "$rf" .md): $(head -20 "$rf" | grep -v '^---' | grep -v '^$' | head -2 | tr '\n' ' ')
"
    done

    # Build extraction prompt
    EXTRACT_PROMPT="You are a behavioral pattern extraction system. Read this conversation transcript and identify patterns in three types:

1. CORRECTIONS — the user told the agent to stop doing something or do something differently
2. CONFIRMATIONS — the user validated a non-obvious choice the agent made
3. PREFERENCES — how the user wants to work (tone, process, style)

For each pattern, determine the enforcement tier:
- \"mechanical\" — can be enforced by blocking a specific tool or bash pattern (e.g., \"never force-push\" → block 'git push --force')
- \"semantic\" — requires LLM judgment to enforce (e.g., \"use Edit instead of sed for source files\")
- \"advisory\" — can only be reminded, not enforced (e.g., \"prefer concise responses\")

For each pattern, classify into one of four knowledge categories:
- \"strategy\" — how the agent approaches problems (workflow patterns, methodologies)
- \"failure-inheritance\" — patterns of failure to avoid (anti-patterns, things that went wrong)
- \"relational-calibration\" — communication style adaptation (tone, verbosity, explanation depth)
- \"meta-cognition\" — which heuristics proved reliable vs. which sounded plausible but failed

EXISTING RULES (do not duplicate):
${EXISTING_RULES}

RECENT TRANSCRIPT:
${TRANSCRIPT_WINDOW}

Output ONLY valid JSON (no markdown fencing):
{
  \"rules\": [
    {
      \"id\": \"kebab-case-id (e.g., no-force-push, prefer-edit-over-sed)\",
      \"enforcement\": \"mechanical|semantic|advisory\",
      \"category\": \"strategy|failure-inheritance|relational-calibration|meta-cognition\",
      \"type\": \"correction|confirmation|preference\",
      \"scope\": \"global|project\",
      \"summary\": \"one-line rule statement\",
      \"evidence\": \"brief quote from transcript\",
      \"enforcement_directives\": [
        \"block_bash_pattern: regex_pattern\",
        \"block_tool: ToolName\",
        \"require_prior_read: true\",
        \"reason: human-readable explanation\"
      ]
    }
  ]
}

Rules:
- Only extract patterns the user explicitly expressed or clearly demonstrated
- Do not infer preferences from silence or routine acknowledgments
- enforcement_directives are ONLY for mechanical rules — omit for semantic/advisory
- If no patterns found, return {\"rules\": []}
- Do not duplicate rules already in EXISTING RULES above"

    RAW_OUTPUT=$(echo "$EXTRACT_PROMPT" | timeout 60 claude -p --model "$EXTRACTION_MODEL_ID" --output-format json 2>/dev/null) || true
    EXTRACT_RESULT=$(echo "$RAW_OUTPUT" | jq -r 'if type == "array" then .[-1].result // empty else .result // . end' 2>/dev/null) || true
    EXTRACT_RESULT=$(echo "$EXTRACT_RESULT" | sed '/^```/d')

    if ! echo "$EXTRACT_RESULT" | jq empty 2>/dev/null; then
      log_succession_event "extraction_failed" \
        --argjson turn "$TURN_COUNT" \
        --arg error "Invalid JSON from extraction"
      echo "$TRANSCRIPT_SIZE" > "$EXTRACT_OFFSET_FILE"
    else
      RULE_COUNT=$(echo "$EXTRACT_RESULT" | jq '.rules | length' 2>/dev/null) || RULE_COUNT=0

      if [ "$RULE_COUNT" -gt 0 ]; then
        RULES_WRITTEN=0

        while IFS= read -r rule_json; do
          [ -n "$rule_json" ] || continue

          local_id=$(echo "$rule_json" | jq -r '.id')
          local_enforcement=$(echo "$rule_json" | jq -r '.enforcement')
          local_category=$(echo "$rule_json" | jq -r '.category // "strategy"')
          local_type=$(echo "$rule_json" | jq -r '.type')
          local_scope=$(echo "$rule_json" | jq -r '.scope // "project"')
          local_summary=$(echo "$rule_json" | jq -r '.summary')
          local_evidence=$(echo "$rule_json" | jq -r '.evidence')

          # Determine target directory based on scope
          if [ "$local_scope" = "global" ]; then
            TARGET_DIR="${SUCCESSION_GLOBAL_DIR}/rules"
          else
            TARGET_DIR="$RULES_DIR"
          fi
          mkdir -p "$TARGET_DIR"

          RULE_FILE="${TARGET_DIR}/${local_id}.md"

          # Skip if rule file already exists
          if [ -f "$RULE_FILE" ]; then
            continue
          fi

          # Write rule file with frontmatter
          {
            echo "---"
            echo "id: ${local_id}"
            echo "scope: ${local_scope}"
            echo "enforcement: ${local_enforcement}"
            echo "category: ${local_category}"
            echo "type: ${local_type}"
            echo "source:"
            echo "  session: ${SESSION_ID}"
            echo "  timestamp: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
            echo "  evidence: \"${local_evidence}\""
            echo "overrides: []"
            echo "enabled: true"
            echo "effectiveness:"
            echo "  times_followed: 0"
            echo "  times_violated: 0"
            echo "  times_overridden: 0"
            echo "  last_evaluated: null"
            echo "---"
            echo ""
            echo "${local_summary}"

            # Add enforcement section for mechanical rules
            if [ "$local_enforcement" = "mechanical" ]; then
              directives=$(echo "$rule_json" | jq -r '.enforcement_directives // [] | .[]')
              if [ -n "$directives" ]; then
                echo ""
                echo "## Enforcement"
                while IFS= read -r directive; do
                  echo "- ${directive}"
                done <<< "$directives"
              fi
            fi
          } > "$RULE_FILE"

          RULES_WRITTEN=$((RULES_WRITTEN + 1))

          # Log rule creation to meta-cognition audit trail
          log_meta_cognition_event "rule_created" \
            --arg rule_id "$local_id" \
            --arg category "$local_category" \
            --arg source "extraction"
        done < <(echo "$EXTRACT_RESULT" | jq -c '.rules[]')

        if [ "$RULES_WRITTEN" -gt 0 ]; then
          NOTIFICATION_MSG="${NOTIFICATION_MSG:+${NOTIFICATION_MSG}; }Extracted ${RULES_WRITTEN} new rule(s)"

          # Re-compile rules after extraction
          "${SCRIPT_DIR}/succession-resolve.sh" "$CWD" 2>/dev/null || true

          log_succession_event "extraction" \
            --argjson turn "$TURN_COUNT" \
            --argjson rules_written "$RULES_WRITTEN" \
            --argjson total_found "$RULE_COUNT" \
            --arg model "$EXTRACTION_MODEL_ID"
        fi
      fi

      # Update offset
      echo "$TRANSCRIPT_SIZE" > "$EXTRACT_OFFSET_FILE"

      # Consume correction flag (extraction happened)
      rm -f "$CORRECTION_FLAG_FILE"
    fi
  fi
fi

# ============================================================
# PHASE 3: ADVISORY RULE RE-INJECTION
# Every N turns, include advisory rules in additionalContext
# to combat instruction drift at ~150k tokens
# ============================================================

ADDITIONAL_CONTEXT=""

if [ $((TURN_COUNT % REINJECTION_INTERVAL)) -eq 0 ]; then
  ADVISORY_FILE="${COMPILED_DIR}/advisory-summary.md"
  if [ -f "$ADVISORY_FILE" ] && [ -s "$ADVISORY_FILE" ]; then
    ADDITIONAL_CONTEXT=$(cat "$ADVISORY_FILE")
  fi
fi

# ============================================================
# PHASE 4: EFFECTIVENESS COUNTER UPDATE (every N turns)
# ============================================================

if [ $((TURN_COUNT % REINJECTION_INTERVAL)) -eq 0 ]; then
  update_effectiveness_counters "$RULES_DIR" "${SUCCESSION_GLOBAL_DIR}/rules" 2>/dev/null || true
fi

# ============================================================
# OUTPUT
# ============================================================

# Build output JSON
if [ -n "$ADDITIONAL_CONTEXT" ] || [ -n "$NOTIFICATION_MSG" ]; then
  OUTPUT='{}'
  if [ -n "$ADDITIONAL_CONTEXT" ]; then
    OUTPUT=$(echo "$OUTPUT" | jq --arg ctx "$ADDITIONAL_CONTEXT" '.hookSpecificOutput.additionalContext = $ctx')
  fi
  if [ -n "$NOTIFICATION_MSG" ]; then
    OUTPUT=$(echo "$OUTPUT" | jq --arg msg "$NOTIFICATION_MSG" '.hookSpecificOutput.additionalContext = ((.hookSpecificOutput.additionalContext // "") + "\n\n[Succession] " + $msg)')
  fi
  echo "$OUTPUT"
else
  exit 0
fi
