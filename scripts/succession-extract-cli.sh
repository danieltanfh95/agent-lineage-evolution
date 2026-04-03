#!/usr/bin/env bash
# Succession — Retrospective Rule Extraction CLI
# Extracts behavioral rules from past Claude Code transcripts.
#
# Usage:
#   succession-extract-cli.sh <transcript.jsonl>              # Extract from specific file
#   succession-extract-cli.sh --session <id>                  # Find transcript by session ID
#   succession-extract-cli.sh --last                          # Most recent session
#   succession-extract-cli.sh --from-turn <N> <transcript>    # Extract from turn N onward
#   succession-extract-cli.sh --interactive <transcript>      # Interactive exploration
#   succession-extract-cli.sh --apply <transcript>            # Write rules (default: dry run)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/lib.sh"

# --- Parse arguments ---
TRANSCRIPT_PATH=""
FROM_TURN=0
INTERACTIVE=false
APPLY=false
SESSION_ID=""
USE_LAST=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --session)     SESSION_ID="$2"; shift 2 ;;
    --last)        USE_LAST=true; shift ;;
    --from-turn)   FROM_TURN="$2"; shift 2 ;;
    --interactive) INTERACTIVE=true; shift ;;
    --apply)       APPLY=true; shift ;;
    --help|-h)
      echo "Usage: succession-extract-cli.sh [options] [transcript.jsonl]"
      echo ""
      echo "Options:"
      echo "  --session <id>      Find transcript by session ID"
      echo "  --last              Use most recent session in current project"
      echo "  --from-turn <N>     Extract from turn N onward"
      echo "  --interactive       Drop into interactive exploration session"
      echo "  --apply             Write extracted rules to disk (default: dry run)"
      echo "  --help              Show this help"
      exit 0
      ;;
    *)
      if [ -z "$TRANSCRIPT_PATH" ]; then
        TRANSCRIPT_PATH="$1"
      fi
      shift
      ;;
  esac
done

# --- Find transcript ---

# Claude Code transcript directory for the current project
CLAUDE_PROJECTS_DIR="${HOME}/.claude/projects"

find_transcript_by_session() {
  local sid="$1"
  find "$CLAUDE_PROJECTS_DIR" -name "*.jsonl" -exec grep -l "\"session_id\":\"${sid}\"" {} \; 2>/dev/null | head -1
}

find_latest_transcript() {
  # Find the project hash directory that matches CWD
  local cwd_hash_dir=""
  for dir in "$CLAUDE_PROJECTS_DIR"/*/; do
    [ -d "$dir" ] || continue
    # Check if any transcript in this dir references our CWD
    local sample
    sample=$(find "$dir" -name "*.jsonl" -maxdepth 1 2>/dev/null | head -1)
    if [ -n "$sample" ] && head -5 "$sample" | jq -r '.cwd // empty' 2>/dev/null | grep -q "$(pwd)"; then
      cwd_hash_dir="$dir"
      break
    fi
  done

  if [ -z "$cwd_hash_dir" ]; then
    # Fallback: find most recently modified JSONL across all projects
    find "$CLAUDE_PROJECTS_DIR" -name "*.jsonl" -maxdepth 2 2>/dev/null | xargs ls -t 2>/dev/null | head -1
  else
    find "$cwd_hash_dir" -name "*.jsonl" -maxdepth 1 2>/dev/null | xargs ls -t 2>/dev/null | head -1
  fi
}

if [ -n "$SESSION_ID" ]; then
  TRANSCRIPT_PATH=$(find_transcript_by_session "$SESSION_ID")
  if [ -z "$TRANSCRIPT_PATH" ]; then
    echo "Error: No transcript found for session ${SESSION_ID}" >&2
    exit 1
  fi
elif [ "$USE_LAST" = true ]; then
  TRANSCRIPT_PATH=$(find_latest_transcript)
  if [ -z "$TRANSCRIPT_PATH" ]; then
    echo "Error: No transcripts found" >&2
    exit 1
  fi
fi

if [ -z "$TRANSCRIPT_PATH" ] || [ ! -f "$TRANSCRIPT_PATH" ]; then
  echo "Error: No transcript specified or file not found" >&2
  echo "Usage: succession-extract-cli.sh [--last | --session <id> | <path>]" >&2
  exit 1
fi

echo "Analyzing: ${TRANSCRIPT_PATH}"
echo ""

# --- Read and filter transcript ---

# Count total turns
TOTAL_TURNS=$(jq -r 'select(.type == "human" or .type == "assistant") | .type' "$TRANSCRIPT_PATH" 2>/dev/null | wc -l | tr -d ' ')
echo "Total turns: ${TOTAL_TURNS}"

# Extract messages, optionally from a specific turn
if [ "$FROM_TURN" -gt 0 ]; then
  echo "Extracting from turn ${FROM_TURN} onward..."
  TRANSCRIPT_TEXT=$(jq -r '
    select(.type == "human" or .type == "assistant")
    | if .type == "human" then
        "USER: " + ((.message.content // "") | if type == "array" then map(select(.type == "text") | .text) | join(" ") else . end)
      else
        "ASSISTANT: " + ((.message.content // "") | if type == "array" then map(select(.type == "text") | .text) | join(" ") else . end)
      end
  ' "$TRANSCRIPT_PATH" 2>/dev/null | tail -n +"$FROM_TURN" | head -c 200000)
else
  TRANSCRIPT_TEXT=$(jq -r '
    select(.type == "human" or .type == "assistant")
    | if .type == "human" then
        "USER: " + ((.message.content // "") | if type == "array" then map(select(.type == "text") | .text) | join(" ") else . end)
      else
        "ASSISTANT: " + ((.message.content // "") | if type == "array" then map(select(.type == "text") | .text) | join(" ") else . end)
      end
  ' "$TRANSCRIPT_PATH" 2>/dev/null | head -c 200000)
fi

if [ -z "$TRANSCRIPT_TEXT" ]; then
  echo "Error: Could not extract messages from transcript" >&2
  exit 1
fi

CHAR_COUNT=${#TRANSCRIPT_TEXT}
echo "Transcript size: ${CHAR_COUNT} chars (capped at 200KB)"
echo ""

# --- Interactive mode ---
if [ "$INTERACTIVE" = true ]; then
  echo "Starting interactive session with transcript loaded..."
  echo "You can ask questions like:"
  echo "  - What corrections did the user make?"
  echo "  - Why did performance degrade after turn 50?"
  echo "  - Extract rules from turns 30-60"
  echo ""

  SYSTEM_PROMPT="You are analyzing a Claude Code conversation transcript for behavioral pattern extraction. The user will ask you questions about the transcript. Help them identify corrections, preferences, degradation points, and extractable rules.

TRANSCRIPT:
${TRANSCRIPT_TEXT}"

  echo "$SYSTEM_PROMPT" | claude --system-prompt - 2>/dev/null
  exit 0
fi

# --- Batch extraction ---
echo "Running extraction..."

MODEL_ID=$(map_model_id "sonnet")

# Load existing rules for deduplication
EXISTING_RULES=""
for rf in "${PWD}/.succession/rules"/*.md "${HOME}/.succession/rules"/*.md; do
  [ -f "$rf" ] || continue
  EXISTING_RULES+="- $(basename "$rf" .md): $(head -20 "$rf" | grep -v '^---' | grep -v '^$' | head -2 | tr '\n' ' ')
"
done

EXTRACT_PROMPT="You are a behavioral pattern extraction system analyzing a past conversation transcript. Identify:

1. CORRECTIONS — user told the agent to stop/change something
2. CONFIRMATIONS — user validated a non-obvious agent choice
3. PREFERENCES — how the user wants to work (tone, process, style)
4. DEGRADATION — points where agent behavior noticeably worsened (instruction drift, repetition, ignoring rules)

For each pattern, determine enforcement tier:
- \"mechanical\" — enforceable by blocking a tool/command pattern
- \"semantic\" — requires LLM judgment to enforce
- \"advisory\" — can only be reminded

EXISTING RULES (do not duplicate):
${EXISTING_RULES}

TRANSCRIPT:
${TRANSCRIPT_TEXT}

Output ONLY valid JSON (no markdown fencing):
{
  \"rules\": [
    {
      \"id\": \"kebab-case-id\",
      \"enforcement\": \"mechanical|semantic|advisory\",
      \"type\": \"correction|confirmation|preference\",
      \"scope\": \"global|project\",
      \"summary\": \"one-line rule statement\",
      \"evidence\": \"brief quote from transcript\",
      \"enforcement_directives\": [\"only for mechanical rules\"]
    }
  ],
  \"degradation_points\": [
    {
      \"approximate_turn\": 42,
      \"description\": \"Agent started ignoring rule X after this point\",
      \"possible_cause\": \"Context window filled with code output\"
    }
  ],
  \"summary\": \"Brief overall assessment of the session\"
}"

RAW_OUTPUT=$(echo "$EXTRACT_PROMPT" | timeout 120 claude -p --model "$MODEL_ID" --output-format json 2>/dev/null) || true
EXTRACT_RESULT=$(echo "$RAW_OUTPUT" | jq -r 'if type == "array" then .[-1].result // empty else .result // . end' 2>/dev/null) || true
EXTRACT_RESULT=$(echo "$EXTRACT_RESULT" | sed '/^```/d')

if ! echo "$EXTRACT_RESULT" | jq empty 2>/dev/null; then
  echo "Error: Extraction returned invalid JSON" >&2
  echo "Raw output:" >&2
  echo "$RAW_OUTPUT" | head -20 >&2
  exit 1
fi

# --- Display results ---
echo ""
echo "=== Extraction Results ==="
echo ""

# Summary
SUMMARY=$(echo "$EXTRACT_RESULT" | jq -r '.summary // "No summary"')
echo "Summary: ${SUMMARY}"
echo ""

# Degradation points
DEG_COUNT=$(echo "$EXTRACT_RESULT" | jq '.degradation_points // [] | length')
if [ "$DEG_COUNT" -gt 0 ]; then
  echo "--- Degradation Points ---"
  echo "$EXTRACT_RESULT" | jq -r '.degradation_points[] | "  Turn ~\(.approximate_turn): \(.description) (\(.possible_cause))"'
  echo ""
fi

# Rules
RULE_COUNT=$(echo "$EXTRACT_RESULT" | jq '.rules | length')
echo "--- Extracted Rules (${RULE_COUNT}) ---"
echo ""

echo "$EXTRACT_RESULT" | jq -r '.rules[] | "[\(.enforcement)] \(.id): \(.summary)\n  Type: \(.type) | Scope: \(.scope)\n  Evidence: \(.evidence)\n"'

# --- Apply rules ---
if [ "$APPLY" = true ] && [ "$RULE_COUNT" -gt 0 ]; then
  echo "=== Writing Rules ==="
  RULES_WRITTEN=0

  while IFS= read -r rule_json; do
    [ -n "$rule_json" ] || continue

    rid=$(echo "$rule_json" | jq -r '.id')
    renforcement=$(echo "$rule_json" | jq -r '.enforcement')
    rtype=$(echo "$rule_json" | jq -r '.type')
    rscope=$(echo "$rule_json" | jq -r '.scope // "project"')
    rsummary=$(echo "$rule_json" | jq -r '.summary')
    revidence=$(echo "$rule_json" | jq -r '.evidence')

    if [ "$rscope" = "global" ]; then
      TARGET_DIR="${HOME}/.succession/rules"
    else
      TARGET_DIR="${PWD}/.succession/rules"
    fi
    mkdir -p "$TARGET_DIR"

    RULE_FILE="${TARGET_DIR}/${rid}.md"
    if [ -f "$RULE_FILE" ]; then
      echo "  SKIP (exists): ${rid}"
      continue
    fi

    {
      echo "---"
      echo "id: ${rid}"
      echo "scope: ${rscope}"
      echo "enforcement: ${renforcement}"
      echo "type: ${rtype}"
      echo "source:"
      echo "  session: retrospective"
      echo "  timestamp: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
      echo "  evidence: \"${revidence}\""
      echo "overrides: []"
      echo "enabled: true"
      echo "---"
      echo ""
      echo "${rsummary}"

      if [ "$renforcement" = "mechanical" ]; then
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

    echo "  WROTE: ${RULE_FILE}"
    RULES_WRITTEN=$((RULES_WRITTEN + 1))
  done < <(echo "$EXTRACT_RESULT" | jq -c '.rules[]')

  echo ""
  echo "Written ${RULES_WRITTEN} rule(s). Run 'succession-resolve.sh ${PWD}' to compile."
else
  if [ "$RULE_COUNT" -gt 0 ]; then
    echo "Dry run — use --apply to write these rules to disk."
  fi
fi
