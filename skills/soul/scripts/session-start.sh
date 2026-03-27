#!/usr/bin/env bash
# SOUL Skill — SessionStart Hook
# Assembles genome fragments + repo soul into additionalContext
# Input: JSON on stdin with session_id, cwd, etc.
# Output: JSON with hookSpecificOutput.additionalContext

set -euo pipefail

INPUT=$(cat)
CWD=$(echo "$INPUT" | jq -r '.cwd // ""')
SOUL_DIR="${CWD}/.soul"
CONFIG_FILE="${SOUL_DIR}/config.json"
GLOBAL_GENOME_DIR="${HOME}/.soul/genome"

assembled=""

# --- If no .soul directory, prompt setup ---
if [ ! -d "$SOUL_DIR" ]; then
  jq -n '{
    hookSpecificOutput: {
      hookEventName: "SessionStart",
      additionalContext: "SOUL is installed but not yet configured for this project. Suggest the user run /soul setup to configure persistent memory."
    }
  }'
  exit 0
fi

# --- Phase 1: Assemble genome fragments (general → specific) ---

if [ -d "$GLOBAL_GENOME_DIR" ]; then
  if [ -f "$CONFIG_FILE" ]; then
    genome_order=$(jq -r '.genome.order // [] | .[]' "$CONFIG_FILE" 2>/dev/null)
  else
    genome_order=""
  fi

  if [ -n "$genome_order" ]; then
    while IFS= read -r name; do
      genome_file="${GLOBAL_GENOME_DIR}/${name}.md"
      if [ -f "$genome_file" ]; then
        assembled+="
--- GENOME: ${name} ---
$(cat "$genome_file")
"
      fi
    done <<< "$genome_order"
  else
    for genome_file in "$GLOBAL_GENOME_DIR"/*.md; do
      if [ -f "$genome_file" ]; then
        name=$(basename "$genome_file" .md)
        assembled+="
--- GENOME: ${name} ---
$(cat "$genome_file")
"
      fi
    done
  fi
fi

# --- Phase 2: Append repo-specific soul ---

if [ -f "${SOUL_DIR}/SOUL.md" ]; then
  assembled+="
--- REPO SOUL ---
$(cat "${SOUL_DIR}/SOUL.md")
"
fi

# --- Phase 3: Append invariants ---

if [ -d "${SOUL_DIR}/invariants" ]; then
  invariants=""
  for inv_file in "${SOUL_DIR}/invariants"/*.md; do
    if [ -f "$inv_file" ]; then
      name=$(basename "$inv_file" .md)
      invariants+="
### ${name}
$(cat "$inv_file")
"
    fi
  done

  if [ -n "$invariants" ]; then
    assembled+="
--- INVARIANTS (user-defined rules) ---
The following rules MUST be followed at all times.
${invariants}"
  fi
fi

# --- Phase 4: Conflict resolution ---

assembled+="

--- SOUL CONFLICT RESOLUTION ---
When genome fragments and the repo soul conflict, the repo soul (SOUL.md) takes precedence."

# --- Output ---

if [ -n "$assembled" ]; then
  jq -n --arg context "$assembled" '{
    hookSpecificOutput: {
      hookEventName: "SessionStart",
      additionalContext: $context
    }
  }'
else
  exit 0
fi
