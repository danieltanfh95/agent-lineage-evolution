#!/usr/bin/env bash
# Succession — Skill Extraction CLI
# Extracts replayable skill bundles (SKILL.md) from past Claude Code transcripts.
# A "skill" is a behavioral pattern + domain knowledge for a specific task type.
#
# Usage:
#   succession-skill-extract.sh <transcript.jsonl>              # Extract skill
#   succession-skill-extract.sh --name "deploy-flow" <file>     # Name the skill
#   succession-skill-extract.sh --interactive <file>             # Explore first
#   succession-skill-extract.sh --apply <file>                   # Write SKILL.md
#   succession-skill-extract.sh --scope global <file>            # Save to global skills

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/lib.sh"

# --- Parse arguments ---
TRANSCRIPT_PATH=""
SKILL_NAME=""
INTERACTIVE=false
APPLY=false
SCOPE="project"
FROM_TURN=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --name)         SKILL_NAME="$2"; shift 2 ;;
    --interactive)  INTERACTIVE=true; shift ;;
    --apply)        APPLY=true; shift ;;
    --scope)        SCOPE="$2"; shift 2 ;;
    --from-turn)    FROM_TURN="$2"; shift 2 ;;
    --last)
      # Find latest transcript (reuse logic from extract-cli)
      CLAUDE_PROJECTS_DIR="${HOME}/.claude/projects"
      TRANSCRIPT_PATH=$(find "$CLAUDE_PROJECTS_DIR" -name "*.jsonl" -maxdepth 2 2>/dev/null | xargs ls -t 2>/dev/null | head -1)
      shift
      ;;
    --help|-h)
      echo "Usage: succession-skill-extract.sh [options] [transcript.jsonl]"
      echo ""
      echo "Options:"
      echo "  --name <name>       Name for the extracted skill (auto-generated if omitted)"
      echo "  --interactive       Drop into interactive exploration session"
      echo "  --apply             Write SKILL.md to disk (default: dry run)"
      echo "  --scope <scope>     global or project (default: project)"
      echo "  --from-turn <N>     Analyze from turn N onward"
      echo "  --last              Use most recent session"
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

if [ -z "$TRANSCRIPT_PATH" ] || [ ! -f "$TRANSCRIPT_PATH" ]; then
  echo "Error: No transcript specified or file not found" >&2
  echo "Usage: succession-skill-extract.sh [--last | <path>]" >&2
  exit 1
fi

echo "Analyzing: ${TRANSCRIPT_PATH}"
echo ""

# --- Read transcript ---
if [ "$FROM_TURN" -gt 0 ]; then
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

echo "Transcript size: ${#TRANSCRIPT_TEXT} chars"
echo ""

# --- Interactive mode ---
if [ "$INTERACTIVE" = true ]; then
  echo "Starting interactive session for skill extraction..."
  echo "You can ask questions like:"
  echo "  - What task was being accomplished?"
  echo "  - What workflow pattern emerged?"
  echo "  - What domain knowledge was used?"
  echo "  - Extract a skill from turns 10-40"
  echo ""

  SYSTEM_PROMPT="You are analyzing a Claude Code conversation transcript to extract a replayable SKILL. A skill is a bundle of: (1) trigger conditions — when this skill applies, (2) workflow steps — the behavioral pattern, (3) domain knowledge — facts learned, (4) rules — corrections specific to this task type.

Help the user understand what happened in the session and extract a skill they can reuse.

TRANSCRIPT:
${TRANSCRIPT_TEXT}"

  echo "$SYSTEM_PROMPT" | claude --system-prompt - 2>/dev/null
  exit 0
fi

# --- Batch extraction ---
echo "Extracting skill..."

MODEL_ID=$(map_model_id "sonnet")

EXTRACT_PROMPT="You are a skill extraction system. Analyze this conversation transcript and extract a replayable SKILL — a bundle of behavioral patterns and domain knowledge that can be applied to similar tasks in the future.

A skill has four components:
1. CONTEXT — when this skill applies (trigger conditions, task type, file patterns)
2. STEPS — the workflow/behavioral pattern observed (what the agent did, in what order)
3. KNOWLEDGE — domain facts learned during the task (not behavioral rules)
4. RULES — corrections and preferences specific to this task type

TRANSCRIPT:
${TRANSCRIPT_TEXT}

Output ONLY valid JSON (no markdown fencing):
{
  \"skill_name\": \"kebab-case name (e.g., express-api-debugging, react-component-refactor)\",
  \"description\": \"one-line description of what this skill does\",
  \"context\": {
    \"trigger\": \"when should this skill activate (e.g., 'when debugging Express API endpoints')\",
    \"file_patterns\": [\"optional glob patterns that indicate relevance (e.g., 'routes/*.ts')\"],
    \"keywords\": [\"words that suggest this skill applies\"]
  },
  \"steps\": [
    \"Step 1: what to do first\",
    \"Step 2: what to do next\"
  ],
  \"knowledge\": [
    \"Domain fact learned (e.g., 'Redis cache TTL defaults to 300s in this project')\"
  ],
  \"rules\": [
    {
      \"rule\": \"correction or preference specific to this task type\",
      \"enforcement\": \"mechanical|semantic|advisory\"
    }
  ],
  \"summary\": \"overall assessment of what was accomplished and how\"
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
echo "=== Extracted Skill ==="
echo ""

EXTRACTED_NAME=$(echo "$EXTRACT_RESULT" | jq -r '.skill_name')
DESCRIPTION=$(echo "$EXTRACT_RESULT" | jq -r '.description')
SUMMARY=$(echo "$EXTRACT_RESULT" | jq -r '.summary // ""')

# Use provided name or extracted name
FINAL_NAME="${SKILL_NAME:-${EXTRACTED_NAME}}"

echo "Name: ${FINAL_NAME}"
echo "Description: ${DESCRIPTION}"
echo ""

echo "--- Context ---"
echo "$EXTRACT_RESULT" | jq -r '.context | "  Trigger: \(.trigger)\n  Files: \(.file_patterns // [] | join(", "))\n  Keywords: \(.keywords // [] | join(", "))"'
echo ""

echo "--- Steps ---"
echo "$EXTRACT_RESULT" | jq -r '.steps[] | "  \(. )"'
echo ""

echo "--- Knowledge ---"
echo "$EXTRACT_RESULT" | jq -r '.knowledge[] | "  - \(.)"'
echo ""

RULE_COUNT=$(echo "$EXTRACT_RESULT" | jq '.rules | length')
if [ "$RULE_COUNT" -gt 0 ]; then
  echo "--- Rules (${RULE_COUNT}) ---"
  echo "$EXTRACT_RESULT" | jq -r '.rules[] | "  [\(.enforcement)] \(.rule)"'
  echo ""
fi

if [ -n "$SUMMARY" ]; then
  echo "Summary: ${SUMMARY}"
  echo ""
fi

# --- Generate SKILL.md ---
generate_skill_md() {
  local trigger context_trigger file_patterns keywords

  context_trigger=$(echo "$EXTRACT_RESULT" | jq -r '.context.trigger')
  file_patterns=$(echo "$EXTRACT_RESULT" | jq -r '.context.file_patterns // [] | join(", ")')
  keywords=$(echo "$EXTRACT_RESULT" | jq -r '.context.keywords // [] | join(", ")')

  cat << SKILL_EOF
---
name: ${FINAL_NAME}
description: "${DESCRIPTION}"
---

# ${FINAL_NAME}

${DESCRIPTION}

## When to Use

${context_trigger}

$([ -n "$file_patterns" ] && echo "File patterns: ${file_patterns}")
$([ -n "$keywords" ] && echo "Keywords: ${keywords}")

## Workflow

$(echo "$EXTRACT_RESULT" | jq -r '.steps | to_entries[] | "\(.key + 1). \(.value)"')

## Knowledge

$(echo "$EXTRACT_RESULT" | jq -r '.knowledge[] | "- \(.)"')

$(if [ "$RULE_COUNT" -gt 0 ]; then
  echo "## Rules"
  echo ""
  echo "$EXTRACT_RESULT" | jq -r '.rules[] | "- [\(.enforcement)] \(.rule)"'
fi)
SKILL_EOF
}

# --- Apply ---
if [ "$APPLY" = true ]; then
  if [ "$SCOPE" = "global" ]; then
    TARGET_DIR="${HOME}/.succession/skills/${FINAL_NAME}"
  else
    TARGET_DIR="${PWD}/.succession/skills/${FINAL_NAME}"
  fi

  mkdir -p "$TARGET_DIR"
  SKILL_FILE="${TARGET_DIR}/SKILL.md"

  generate_skill_md > "$SKILL_FILE"
  echo "Wrote skill to: ${SKILL_FILE}"

  # Also write any mechanical rules as separate rule files
  if [ "$RULE_COUNT" -gt 0 ]; then
    while IFS= read -r rule_json; do
      renforcement=$(echo "$rule_json" | jq -r '.enforcement')
      if [ "$renforcement" = "mechanical" ]; then
        echo "  Note: Mechanical rules from skills should be added as separate rule files"
        echo "  Use 'succession add' to create them"
        break
      fi
    done < <(echo "$EXTRACT_RESULT" | jq -c '.rules[]')
  fi
else
  echo "--- Generated SKILL.md (preview) ---"
  echo ""
  generate_skill_md
  echo ""
  echo "Dry run — use --apply to write to disk."
fi
