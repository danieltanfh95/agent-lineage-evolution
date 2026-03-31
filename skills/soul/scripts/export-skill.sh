#!/usr/bin/env bash
# SOUL Framework — Skill Export
# Exports a skill from ## Skills in SOUL.md as a standalone, shareable Claude Code skill.
# The exported SKILL.md has all relevant knowledge baked in (no !cat directives).
#
# Usage: export-skill.sh <skill-name> [--to <path>] [--genome] [--quiet]
#   --to <path>    Write to a custom directory (default: .soul/exports/<skill-name>/)
#   --genome       Write to ~/.soul/genome/skills/<skill-name>/
#   --quiet        Suppress stdout messages (for automated use from compact.sh)

set -euo pipefail

# --- Parse arguments ---
SKILL_NAME=""
OUTPUT_DIR=""
GENOME_MODE=false
QUIET=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --to)
      OUTPUT_DIR="$2"
      shift 2
      ;;
    --genome)
      GENOME_MODE=true
      shift
      ;;
    --quiet)
      QUIET=true
      shift
      ;;
    -*)
      echo "Unknown option: $1" >&2
      exit 1
      ;;
    *)
      SKILL_NAME="$1"
      shift
      ;;
  esac
done

if [ -z "$SKILL_NAME" ]; then
  echo "Usage: export-skill.sh <skill-name> [--to <path>] [--genome] [--quiet]" >&2
  exit 1
fi

# --- Locate SOUL.md ---
# When called from compact.sh, CWD is set. When called manually, use git root or pwd.
if [ -z "${CWD:-}" ]; then
  CWD=$(git rev-parse --show-toplevel 2>/dev/null || pwd)
fi
SOUL_DIR="${CWD}/.soul"
SOUL_FILE="${SOUL_DIR}/SOUL.md"
CONFIG_FILE="${SOUL_DIR}/config.json"

if [ ! -f "$SOUL_FILE" ]; then
  echo "Error: No SOUL.md found at ${SOUL_FILE}" >&2
  exit 1
fi

# --- Determine output directory ---
if [ "$GENOME_MODE" = true ]; then
  OUTPUT_DIR="${HOME}/.soul/genome/skills/${SKILL_NAME}"
elif [ -z "$OUTPUT_DIR" ]; then
  OUTPUT_DIR="${SOUL_DIR}/exports/${SKILL_NAME}"
fi

# --- Read compaction model from config ---
EXPORT_MODEL="sonnet"
if [ -f "$CONFIG_FILE" ]; then
  EXPORT_MODEL=$(jq -r '.compaction.model // "sonnet"' "$CONFIG_FILE")
fi
case "$EXPORT_MODEL" in
  haiku)   EXPORT_MODEL_ID="claude-haiku-4-5-20251001" ;;
  sonnet)  EXPORT_MODEL_ID="claude-sonnet-4-6" ;;
  opus)    EXPORT_MODEL_ID="claude-opus-4-6" ;;
  *)       EXPORT_MODEL_ID="claude-sonnet-4-6" ;;
esac

# --- Extract skill definition from ## Skills ---
skills_section=$(sed -n '/^## Skills$/,/^## /{ /^## Skills$/d; /^## /d; p; }' "$SOUL_FILE")

if [ -z "$skills_section" ]; then
  echo "Error: No ## Skills section found in SOUL.md" >&2
  exit 1
fi

# Find the specific skill block
skill_body=""
current_name=""
current_body=""

while IFS= read -r line || [ -n "$line" ]; do
  if [[ "$line" =~ ^###[[:space:]]+(.*) ]]; then
    if [ "$current_name" = "$SKILL_NAME" ]; then
      skill_body="$current_body"
      break
    fi
    current_name="${BASH_REMATCH[1]}"
    current_body=""
  else
    current_body+="${line}
"
  fi
done <<< "$skills_section"

# Check the last skill block
if [ -z "$skill_body" ] && [ "$current_name" = "$SKILL_NAME" ]; then
  skill_body="$current_body"
fi

if [ -z "$skill_body" ]; then
  echo "Error: Skill '${SKILL_NAME}' not found in ## Skills section" >&2
  exit 1
fi

# --- Parse skill metadata ---
description=$(echo "$skill_body" | sed '/^$/d' | head -1)
has_fork=$(echo "$skill_body" | grep -c '^mode: fork$' || true)
role_body=$(echo "$skill_body" | grep -v '^mode: fork$' | sed -e '/^$/N;/^\n$/d' -e 's/^[[:space:]]*$//')

# --- Gather knowledge context ---
accumulated_knowledge=$(sed -n '/^## Accumulated Knowledge$/,/^## /{ /^## Accumulated Knowledge$/d; /^## /d; p; }' "$SOUL_FILE")
predecessor_warnings=$(sed -n '/^## Predecessor Warnings$/,/^## /{ /^## Predecessor Warnings$/d; /^## /d; p; }' "$SOUL_FILE")

invariants=""
if [ -d "${SOUL_DIR}/invariants" ]; then
  for inv_file in "${SOUL_DIR}/invariants"/*.md; do
    if [ -f "$inv_file" ]; then
      invariants+="$(cat "$inv_file")
"
    fi
  done
fi

# --- Filter for relevance via claude -p ---
FILTER_PROMPT="You are a skill knowledge extractor for the SOUL framework. Given a skill's role description and a project's accumulated knowledge, extract ONLY the knowledge directly relevant to this skill's domain.

SKILL NAME: ${SKILL_NAME}
SKILL ROLE:
${role_body}

ACCUMULATED KNOWLEDGE:
${accumulated_knowledge}

PREDECESSOR WARNINGS:
${predecessor_warnings}

INVARIANTS:
${invariants}

For each section, select only the bullets/rules that are directly relevant to what this skill does. A reviewer skill needs knowledge about code review patterns, not deployment procedures. An explorer skill needs knowledge about codebase navigation, not API design.

Do not rephrase — preserve exact wording. If nothing is relevant in a section, return an empty array.

Output ONLY valid JSON (no markdown fencing):
{
  \"relevant_knowledge\": [\"bullet 1\", \"bullet 2\"],
  \"relevant_warnings\": [\"warning 1\"],
  \"relevant_invariants\": [\"invariant 1\"]
}"

RAW_OUTPUT=$(echo "$FILTER_PROMPT" | timeout 60 claude -p --model "$EXPORT_MODEL_ID" --output-format json 2>/dev/null) || true
FILTER_RESULT=$(echo "$RAW_OUTPUT" | jq -r 'if type == "array" then .[-1].result // empty else .result // . end' 2>/dev/null) || true

# Parse the filtered results
relevant_knowledge=""
relevant_warnings=""
relevant_invariants=""

if [ -n "$FILTER_RESULT" ]; then
  # Extract arrays from the JSON response
  relevant_knowledge=$(echo "$FILTER_RESULT" | jq -r '.relevant_knowledge // [] | .[] | "- " + .' 2>/dev/null) || true
  relevant_warnings=$(echo "$FILTER_RESULT" | jq -r '.relevant_warnings // [] | .[] | "- " + .' 2>/dev/null) || true
  relevant_invariants=$(echo "$FILTER_RESULT" | jq -r '.relevant_invariants // [] | .[] | "- " + .' 2>/dev/null) || true
fi

# Count items for reporting
n_knowledge=$(echo "$relevant_knowledge" | grep -c '^- ' || true)
n_warnings=$(echo "$relevant_warnings" | grep -c '^- ' || true)
n_invariants=$(echo "$relevant_invariants" | grep -c '^- ' || true)

# --- Assemble exported SKILL.md ---
mkdir -p "$OUTPUT_DIR"

{
  echo '---'
  echo "name: ${SKILL_NAME}"
  echo "description: \"${description}\""
  if [ "$has_fork" -ge 1 ]; then
    echo 'context: fork'
    echo 'agent: Explore'
  fi
  echo '---'
  echo ''
  echo '# Role'
  echo "${role_body}"

  if [ -n "$relevant_knowledge" ]; then
    echo ''
    echo '# Relevant Knowledge'
    echo "${relevant_knowledge}"
  fi

  if [ -n "$relevant_warnings" ]; then
    echo ''
    echo '# Warnings'
    echo "${relevant_warnings}"
  fi

  if [ -n "$relevant_invariants" ]; then
    echo ''
    echo '# Invariants'
    echo "${relevant_invariants}"
  fi
} > "${OUTPUT_DIR}/SKILL.md"

# --- Write PROVENANCE.md ---
mkdir -p "${OUTPUT_DIR}/references"

{
  echo '# Provenance'
  echo ''
  echo "Exported from SOUL framework on $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo ''
  if git -C "$CWD" rev-parse --git-dir &>/dev/null 2>&1; then
    origin=$(git -C "$CWD" remote get-url origin 2>/dev/null || echo "unknown")
    commit=$(git -C "$CWD" rev-parse HEAD 2>/dev/null || echo "unknown")
    echo "- **Source repo:** ${origin}"
    echo "- **Commit:** ${commit}"
  fi
  echo "- **Skill name:** ${SKILL_NAME}"
  echo "- **Knowledge points:** ${n_knowledge}"
  echo "- **Warnings:** ${n_warnings}"
  echo "- **Invariants:** ${n_invariants}"
} > "${OUTPUT_DIR}/references/PROVENANCE.md"

# --- Report ---
if [ "$QUIET" != true ]; then
  echo "Exported skill '${SKILL_NAME}' to ${OUTPUT_DIR}/"
  echo "  ${n_knowledge} knowledge points, ${n_warnings} warnings, ${n_invariants} invariants"
  if [ "$GENOME_MODE" = true ]; then
    echo "  Skill is now available in all your projects via genome cascade."
  else
    echo "  Push to GitHub and others can install with: npx skills add <owner>/<repo> --skill ${SKILL_NAME}"
  fi
fi
