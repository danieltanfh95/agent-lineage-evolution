#!/usr/bin/env bash
# Imprint — Cascade Resolution
# Reads rule files from global (~/.imprint/rules/) and project (.imprint/rules/),
# applies cascade logic, and compiles into three enforcement artifacts:
#   1. tool-rules.json      — mechanical PreToolUse enforcement
#   2. semantic-rules.md    — semantic PreToolUse prompt hook
#   3. advisory-summary.md  — advisory re-injection via additionalContext
#
# Usage: source this file, then call resolve_rules "$PROJECT_DIR"
# Or run standalone: ./imprint-resolve.sh <project_dir>

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/lib.sh"

# Load all rule files from a directory into a JSON array.
# Each entry: {id, scope, enforcement, type, overrides, enabled, body, file, ...frontmatter}
load_rules_from_dir() {
  local dir="$1"
  local scope="$2"  # "global" or "project"
  local rules="[]"

  [ -d "$dir" ] || { echo "$rules"; return; }

  for rule_file in "$dir"/*.md; do
    [ -f "$rule_file" ] || continue

    local frontmatter body
    frontmatter=$(parse_rule_frontmatter "$rule_file")
    body=$(parse_rule_body "$rule_file")

    # Merge scope, file path, and body into the frontmatter JSON
    rules=$(echo "$rules" | jq \
      --argjson fm "$frontmatter" \
      --arg scope "$scope" \
      --arg file "$rule_file" \
      --arg body "$body" \
      '. + [$fm + {scope: $scope, file: $file, body: $body}]')
  done

  echo "$rules"
}

# Main resolution: cascade global + project rules, compile artifacts.
resolve_rules() {
  local project_dir="$1"
  local global_dir="${IMPRINT_GLOBAL_DIR}/rules"
  local project_rules_dir="${project_dir}/.imprint/rules"
  local compiled_dir="${project_dir}/.imprint/compiled"

  mkdir -p "$compiled_dir"

  # Step 1: Load rules from both levels
  local global_rules project_rules
  global_rules=$(load_rules_from_dir "$global_dir" "global")
  project_rules=$(load_rules_from_dir "$project_rules_dir" "project")

  # Step 2: Merge with cascade — project rules with same id override global
  local merged
  merged=$(jq -n \
    --argjson global "$global_rules" \
    --argjson project "$project_rules" '
    # Index project rules by id
    ($project | map({(.id): .}) | add // {}) as $proj_idx |
    # Collect all override targets
    ($project | map(.overrides // []) | add // []) as $overridden |
    # Keep global rules not overridden by project rules (by id or explicit overrides)
    [$global[] | select(
      ($proj_idx[.id] == null) and
      ([.id] | inside($overridden) | not)
    )] + $project |
    # Filter to enabled rules
    [.[] | select(.enabled != false)]
  ')

  # Step 3: Partition by enforcement tier
  local mechanical semantic advisory
  mechanical=$(echo "$merged" | jq '[.[] | select(.enforcement == "mechanical")]')
  semantic=$(echo "$merged" | jq '[.[] | select(.enforcement == "semantic")]')
  advisory=$(echo "$merged" | jq '[.[] | select(.enforcement == "advisory")]')

  # Step 4: Compile mechanical rules → tool-rules.json
  # Extract enforcement directives from rule bodies
  local tool_rules="[]"
  while IFS= read -r rule_json; do
    [ -n "$rule_json" ] || continue
    local body id
    body=$(echo "$rule_json" | jq -r '.body')
    id=$(echo "$rule_json" | jq -r '.id')

    # Parse ## Enforcement section for block_bash_pattern, block_tool, require_prior_read
    while IFS= read -r line; do
      if echo "$line" | grep -q '^- block_bash_pattern:'; then
        local pattern reason
        pattern=$(echo "$line" | sed 's/^- block_bash_pattern: *//' | sed 's/^"//' | sed 's/"$//')
        reason=$(echo "$body" | grep '^- reason:' | head -1 | sed 's/^- reason: *//' | sed 's/^"//' | sed 's/"$//')
        tool_rules=$(echo "$tool_rules" | jq \
          --arg pattern "$pattern" \
          --arg reason "${reason:-Blocked by rule: $id}" \
          --arg source "$id" \
          '. + [{"block_bash_pattern": $pattern, "reason": $reason, "source": $source}]')
      elif echo "$line" | grep -q '^- block_tool:'; then
        local tool reason
        tool=$(echo "$line" | sed 's/^- block_tool: *//' | sed 's/^"//' | sed 's/"$//')
        reason=$(echo "$body" | grep '^- reason:' | head -1 | sed 's/^- reason: *//' | sed 's/^"//' | sed 's/"$//')
        tool_rules=$(echo "$tool_rules" | jq \
          --arg tool "$tool" \
          --arg reason "${reason:-Blocked by rule: $id}" \
          --arg source "$id" \
          '. + [{"block_tool": $tool, "reason": $reason, "source": $source}]')
      elif echo "$line" | grep -q '^- require_prior_read'; then
        local reason
        reason=$(echo "$body" | grep '^- reason:' | head -1 | sed 's/^- reason: *//' | sed 's/^"//' | sed 's/"$//')
        tool_rules=$(echo "$tool_rules" | jq \
          --arg reason "${reason:-Must read before editing}" \
          --arg source "$id" \
          '. + [{"require_prior_read": true, "reason": $reason, "source": $source}]')
      fi
    done <<< "$body"
  done < <(echo "$mechanical" | jq -c '.[]')

  echo "$tool_rules" | jq '.' > "${compiled_dir}/tool-rules.json"

  # Step 5: Compile semantic rules → semantic-rules.md
  {
    echo "# Semantic Rules"
    echo ""
    echo "Evaluate the tool call against these rules. If any rule is violated, respond with {\"ok\": false, \"reason\": \"<which rule and why>\"}."
    echo ""
    while IFS= read -r rule_json; do
      [ -n "$rule_json" ] || continue
      local id body
      id=$(echo "$rule_json" | jq -r '.id')
      body=$(echo "$rule_json" | jq -r '.body')
      echo "## ${id}"
      echo "$body" | grep -v '^## Enforcement' | grep -v '^- block_' | grep -v '^- require_' | grep -v '^- reason:' | sed '/^$/N;/^\n$/d'
      echo ""
    done < <(echo "$semantic" | jq -c '.[]')
  } > "${compiled_dir}/semantic-rules.md"

  # Step 6: Compile advisory rules → advisory-summary.md
  {
    echo "# Active Rules (Reminder)"
    echo ""
    echo "The following rules are currently active. Follow them in your responses."
    echo ""
    while IFS= read -r rule_json; do
      [ -n "$rule_json" ] || continue
      local id body
      id=$(echo "$rule_json" | jq -r '.id')
      body=$(echo "$rule_json" | jq -r '.body' | head -3)  # First 3 lines as summary
      echo "- **${id}**: ${body}" | tr '\n' ' '
      echo ""
    done < <(echo "$advisory" | jq -c '.[]')

    # Also include semantic rules as reminders (belt + suspenders)
    while IFS= read -r rule_json; do
      [ -n "$rule_json" ] || continue
      local id body
      id=$(echo "$rule_json" | jq -r '.id')
      body=$(echo "$rule_json" | jq -r '.body' | head -3)
      echo "- **${id}**: ${body}" | tr '\n' ' '
      echo ""
    done < <(echo "$semantic" | jq -c '.[]')
  } > "${compiled_dir}/advisory-summary.md"

  # Log resolution
  local total_count mechanical_count semantic_count advisory_count
  total_count=$(echo "$merged" | jq 'length')
  mechanical_count=$(echo "$mechanical" | jq 'length')
  semantic_count=$(echo "$semantic" | jq 'length')
  advisory_count=$(echo "$advisory" | jq 'length')

  log_imprint_event "resolve" \
    --argjson total "$total_count" \
    --argjson mechanical "$mechanical_count" \
    --argjson semantic "$semantic_count" \
    --argjson advisory "$advisory_count"
}

# Run standalone if invoked directly
if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
  if [ $# -lt 1 ]; then
    echo "Usage: $0 <project_dir>" >&2
    exit 1
  fi
  resolve_rules "$1"
fi
