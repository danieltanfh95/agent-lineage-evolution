#!/usr/bin/env bash
# SOUL Framework — SessionStart Hook
# Assembles genome fragments + repo soul into additionalContext
# Input: JSON on stdin with session_id, cwd, source, etc.
# Output: JSON with hookSpecificOutput.additionalContext

set -euo pipefail

INPUT=$(cat)
CWD=$(echo "$INPUT" | jq -r '.cwd // ""')
SOUL_DIR="${CWD}/.soul"
CONFIG_FILE="${SOUL_DIR}/config.json"
LOG_DIR="${SOUL_DIR}/log"
GLOBAL_GENOME_DIR="${HOME}/.soul/genome"

# Bail if no .soul directory in this repo
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
  rotate_log_if_needed() { :; }
fi

SESSION_ID=$(echo "$INPUT" | jq -r '.session_id // "unknown"')

# --- Log rotation: rotate if > 1MB ---
rotate_log_if_needed

# Track what we load for logging
GENOMES_LOADED=""
SKILLS_GENERATED=""

assembled=""

# --- Phase 1: Assemble genome fragments (general → specific) ---

if [ -d "$GLOBAL_GENOME_DIR" ]; then
  # Read genome order from config, or fall back to alphabetical
  if [ -f "$CONFIG_FILE" ] && jq empty "$CONFIG_FILE" 2>/dev/null; then
    genome_order=$(jq -r '.genome.order // [] | .[]' "$CONFIG_FILE" 2>/dev/null)
  else
    genome_order=""
  fi

  if [ -n "$genome_order" ]; then
    # Load genomes in configured order
    while IFS= read -r name; do
      genome_file="${GLOBAL_GENOME_DIR}/${name}.md"
      if [ -f "$genome_file" ]; then
        assembled+="
--- GENOME: ${name} ---
$(cat "$genome_file")
"
        GENOMES_LOADED="${GENOMES_LOADED:+${GENOMES_LOADED},}${name}"
      fi
    done <<< "$genome_order"
  else
    # Fall back to alphabetical order
    for genome_file in "$GLOBAL_GENOME_DIR"/*.md; do
      if [ -f "$genome_file" ]; then
        name=$(basename "$genome_file" .md)
        assembled+="
--- GENOME: ${name} ---
$(cat "$genome_file")
"
        GENOMES_LOADED="${GENOMES_LOADED:+${GENOMES_LOADED},}${name}"
      fi
    done
  fi
fi

# --- Phase 2: Append repo-specific soul (most specific, wins conflicts) ---

if [ -f "${SOUL_DIR}/SOUL.md" ]; then
  assembled+="
--- REPO SOUL ---
$(cat "${SOUL_DIR}/SOUL.md")
"
fi

# --- Phase 3: Append invariants summary ---

if [ -d "${SOUL_DIR}/invariants" ]; then
  human_invariants=""
  learned_invariants=""
  for inv_file in "${SOUL_DIR}/invariants"/*.md; do
    if [ -f "$inv_file" ]; then
      name=$(basename "$inv_file" .md)
      if [ "$name" = "learned" ]; then
        learned_invariants+="
$(cat "$inv_file")
"
      else
        human_invariants+="
### ${name}
$(cat "$inv_file")
"
      fi
    fi
  done

  if [ -n "$human_invariants" ]; then
    assembled+="
--- INVARIANTS (human-authored, immutable) ---
The following invariants MUST NOT be violated. A conscience audit loop monitors your behavior against these rules. Violations will be blocked and repeated violations will terminate the session.
${human_invariants}"
  fi

  if [ -n "$learned_invariants" ]; then
    assembled+="

--- LEARNED RULES (auto-generated from your corrections) ---
These rules were learned from your past corrections. They are enforced like invariants but you can edit or delete them in .soul/invariants/learned.md.
${learned_invariants}"
  fi
fi

# --- Phase 3b: Generate tool-rules.json from invariant text (for PreToolUse hook) ---

TOOL_RULES_FILE="${SOUL_DIR}/invariants/tool-rules.json"
if [ ! -f "$TOOL_RULES_FILE" ] && [ -d "${SOUL_DIR}/invariants" ]; then
  RULES="[]"
  for inv_file in "${SOUL_DIR}/invariants"/*.md; do
    [ -f "$inv_file" ] || continue
    name=$(basename "$inv_file" .md)
    while IFS= read -r line; do
      line_lower=$(echo "$line" | tr '[:upper:]' '[:lower:]')
      # Agent/subagent blocking
      if echo "$line_lower" | grep -qE '(never|do not|don.t).*(subagent|sub-agent|spawn agent)'; then
        RULES=$(echo "$RULES" | jq --arg reason "$line" --arg src "${name}.md" \
          '. + [{"block_tool": "Agent", "reason": $reason, "source": $src}]')
      fi
      # Force-push blocking
      if echo "$line_lower" | grep -qE '(never|do not|don.t).*force.?push'; then
        RULES=$(echo "$RULES" | jq --arg src "${name}.md" \
          '. + [{"block_bash_pattern": "git push.*(--force|-f)", "reason": "Never force-push without confirmation", "source": $src}]')
      fi
      # --no-verify blocking
      if echo "$line_lower" | grep -qE '(never|do not|don.t).*--no-verify'; then
        RULES=$(echo "$RULES" | jq --arg src "${name}.md" \
          '. + [{"block_bash_pattern": "--no-verify", "reason": "Never skip pre-commit hooks", "source": $src}]')
      fi
      # Read-before-edit
      if echo "$line_lower" | grep -qE 'read.*before.*edit'; then
        RULES=$(echo "$RULES" | jq --arg src "${name}.md" \
          '. + [{"require_prior_read": true, "reason": "Always read a file before editing it", "source": $src}]')
      fi
    done < <(grep '^- ' "$inv_file")
  done
  if [ "$RULES" != "[]" ]; then
    echo "$RULES" | jq '.' > "$TOOL_RULES_FILE"
  fi
fi

# --- Phase 4: Append conflict resolution note ---

assembled+="

--- SOUL CONFLICT RESOLUTION ---
When genome fragments and the repo soul conflict, the repo soul (SOUL.md) takes precedence. More specific always overrides more general."

# --- Phase 5: Genome health check — nudge user if consolidation needed ---

LEARNED_FILE="${GLOBAL_GENOME_DIR}/learned.md"
GENOME_CONSOLIDATE_THRESHOLD=5000

if [ -f "$LEARNED_FILE" ]; then
  LEARNED_SIZE=$(wc -c < "$LEARNED_FILE" | tr -d ' ')
  LEARNED_LINES=$(grep -c '^- ' "$LEARNED_FILE" 2>/dev/null || echo "0")

  if [ "$LEARNED_SIZE" -gt "$GENOME_CONSOLIDATE_THRESHOLD" ]; then
    assembled+="

--- SOUL NOTICE ---
Your genome (learned.md) has accumulated ${LEARNED_LINES} patterns (${LEARNED_SIZE} chars) from sessions across multiple repos. Consider running \`/soul consolidate\` to deduplicate and correlate cross-project patterns."
  fi
fi

# --- Phase 6: Generate Claude Code skills from ## Skills section ---

SOUL_FILE="${SOUL_DIR}/SOUL.md"
if [ -f "$SOUL_FILE" ]; then
  SKILLS_DIR="${CWD}/.claude/skills"

  # Extract the ## Skills section (everything between ## Skills and the next ## or EOF)
  skills_section=$(sed -n '/^## Skills$/,/^## /{ /^## Skills$/d; /^## /d; p; }' "$SOUL_FILE")

  # Emit a skill file from name + body
  emit_skill() {
    local skill_name="$1"
    local body="$2"

    local description
    description=$(echo "$body" | sed '/^$/d' | head -1)
    local has_fork
    has_fork=$(echo "$body" | grep -c '^mode: fork$' || true)
    local role_body
    role_body=$(echo "$body" | grep -v '^mode: fork$' | sed -e '/^$/N;/^\n$/d' -e 's/^[[:space:]]*$//')

    mkdir -p "${SKILLS_DIR}/${skill_name}"
    local skill_file="${SKILLS_DIR}/${skill_name}/SKILL.md"

    {
      echo '---'
      echo '# SOUL-MANAGED — do not edit. Change .soul/SOUL.md ## Skills instead.'
      echo "name: ${skill_name}"
      echo "description: \"${description}\""
      if [ "$has_fork" -ge 1 ]; then
        echo 'context: fork'
        echo 'agent: Explore'
      fi
      echo '---'
      echo ''
      echo '# Role'
      echo "${role_body}"
      echo ''
      echo '# Soul Context'
      echo '!`cat "$CLAUDE_PROJECT_DIR"/.soul/SOUL.md`'
      echo ''
      echo '# Invariants'
      echo '!`cat "$CLAUDE_PROJECT_DIR"/.soul/invariants/*.md`'
    } > "$skill_file"
  }

  if [ -n "$skills_section" ]; then
    current_name=""
    current_body=""

    while IFS= read -r line || [ -n "$line" ]; do
      if [[ "$line" =~ ^###[[:space:]]+(.*) ]]; then
        # Emit previous skill if we have one
        if [ -n "$current_name" ]; then
          emit_skill "$current_name" "$current_body"
        fi
        current_name="${BASH_REMATCH[1]}"
        current_body=""
        SKILLS_GENERATED="${SKILLS_GENERATED:+${SKILLS_GENERATED},}${BASH_REMATCH[1]}"
      else
        current_body+="${line}
"
      fi
    done <<< "$skills_section"

    # Emit the last skill
    if [ -n "$current_name" ]; then
      emit_skill "$current_name" "$current_body"
    fi
  fi
fi

# --- Phase 6b: Load genome-level skills (unless overridden by repo skills) ---

GENOME_SKILLS_DIR="${HOME}/.soul/genome/skills"
if [ -d "$GENOME_SKILLS_DIR" ]; then
  SKILLS_DIR="${SKILLS_DIR:-${CWD}/.claude/skills}"
  for genome_skill_dir in "$GENOME_SKILLS_DIR"/*/; do
    [ -d "$genome_skill_dir" ] || continue
    gskill_name=$(basename "$genome_skill_dir")
    # Skip if repo already defines this skill (repo wins in cascade)
    if [ -f "${SKILLS_DIR}/${gskill_name}/SKILL.md" ]; then
      continue
    fi
    # Copy genome skill to .claude/skills/
    if [ -f "${genome_skill_dir}/SKILL.md" ]; then
      mkdir -p "${SKILLS_DIR}/${gskill_name}"
      cp "${genome_skill_dir}/SKILL.md" "${SKILLS_DIR}/${gskill_name}/SKILL.md"
    fi
  done
fi

# --- Log session start event ---
CONTEXT_SIZE=${#assembled}
GENOMES_JSON=$(echo "$GENOMES_LOADED" | tr ',' '\n' | jq -R . | jq -sc '.')
SKILLS_JSON=$(echo "$SKILLS_GENERATED" | tr ',' '\n' | jq -R 'select(. != "")' | jq -sc '.')
log_soul_event "session_start" \
  --argjson genomes_loaded "$GENOMES_JSON" \
  --argjson context_size_chars "$CONTEXT_SIZE" \
  --argjson skills_generated "$SKILLS_JSON"

# --- Output assembled soul as additionalContext ---

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
