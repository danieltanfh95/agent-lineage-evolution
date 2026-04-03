#!/usr/bin/env bash
# Succession — Shared Hook Library
# Sourced by all hook scripts.
# Provides: map_model_id(), log_succession_event(), log_meta_cognition_event(),
#           parse_rule_frontmatter(), update_effectiveness_counters()

SUCCESSION_GLOBAL_DIR="${HOME}/.succession"
SUCCESSION_PROJECT_DIR=""  # Set by caller from CWD

# Map friendly model names to Claude model IDs
map_model_id() {
  case "$1" in
    haiku)   echo "claude-haiku-4-5-20251001" ;;
    sonnet)  echo "claude-sonnet-4-6" ;;
    opus)    echo "claude-opus-4-6" ;;
    *)       echo "claude-sonnet-4-6" ;;  # Default to Sonnet (better reasoning, saves tokens)
  esac
}

# Log a structured event to the activity log.
# Usage: log_succession_event "event_name" [jq --arg flags...]
# Requires $LOG_DIR and $SESSION_ID to be set by the caller.
log_succession_event() {
  local event="$1"
  shift

  local log_dir="${LOG_DIR:-${SUCCESSION_GLOBAL_DIR}/log}"
  mkdir -p "$log_dir"
  jq -cn \
    --arg ts "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    --arg session "${SESSION_ID:-unknown}" \
    --arg event "$event" \
    "$@" \
    '{timestamp: $ts, session: $session, event: $event} + $ARGS.named' \
    >> "${log_dir}/succession-activity.jsonl" 2>/dev/null || true
}

# Rotate log file if it exceeds 1MB.
rotate_log_if_needed() {
  local log_file="${LOG_DIR:-${SUCCESSION_GLOBAL_DIR}/log}/succession-activity.jsonl"
  if [ -f "$log_file" ]; then
    local size
    size=$(wc -c < "$log_file" | tr -d ' ')
    if [ "$size" -gt 1048576 ]; then
      mv "$log_file" "${log_file}.1"
    fi
  fi
}

# Parse YAML frontmatter from a rule file.
# Outputs JSON with all frontmatter fields.
# Usage: parse_rule_frontmatter /path/to/rule.md
parse_rule_frontmatter() {
  local file="$1"
  local in_frontmatter=false
  local yaml=""

  while IFS= read -r line; do
    if [ "$line" = "---" ]; then
      if [ "$in_frontmatter" = true ]; then
        break  # End of frontmatter
      else
        in_frontmatter=true
        continue
      fi
    fi
    if [ "$in_frontmatter" = true ]; then
      yaml+="${line}
"
    fi
  done < "$file"

  # Convert simple YAML to JSON line-by-line using bash + jq
  local json='{}'
  local current_block=""  # tracks which nested block we're in: source, overrides, effectiveness
  local overrides="[]"
  local source_json='{}'
  local effectiveness_json='{"times_followed":0,"times_violated":0,"times_overridden":0,"last_evaluated":null}'

  while IFS= read -r line; do
    [ -n "$line" ] || continue

    # Handle nested block starts
    if [ "$line" = "source:" ]; then
      current_block="source"
      continue
    fi
    if [ "$line" = "effectiveness:" ]; then
      current_block="effectiveness"
      continue
    fi
    if [ "$line" = "overrides: []" ]; then
      overrides="[]"
      current_block=""
      continue
    fi
    if echo "$line" | grep -q '^overrides:'; then
      current_block="overrides"
      overrides="[]"
      continue
    fi

    # Handle override list items
    if [ "$current_block" = "overrides" ] && echo "$line" | grep -q '^  - '; then
      local item
      item=$(echo "$line" | sed 's/^  - //')
      overrides=$(echo "$overrides" | jq --arg v "$item" '. + [$v]')
      continue
    fi

    # Handle nested block sub-fields (source, effectiveness)
    if [ -n "$current_block" ] && [ "$current_block" != "overrides" ] && echo "$line" | grep -q '^  '; then
      local key val
      key=$(echo "$line" | sed 's/^  //' | cut -d: -f1)
      val=$(echo "$line" | sed 's/^  [^:]*: *//' | sed 's/^"//' | sed 's/"$//')

      if [ "$current_block" = "source" ]; then
        source_json=$(echo "$source_json" | jq --arg k "$key" --arg v "$val" '. + {($k): $v}')
      elif [ "$current_block" = "effectiveness" ]; then
        # Handle null, booleans, and numbers natively
        if [ "$val" = "null" ]; then
          effectiveness_json=$(echo "$effectiveness_json" | jq --arg k "$key" '. + {($k): null}')
        elif [ "$val" = "true" ] || [ "$val" = "false" ]; then
          effectiveness_json=$(echo "$effectiveness_json" | jq --arg k "$key" --argjson v "$val" '. + {($k): $v}')
        elif echo "$val" | grep -qE '^[0-9]+$'; then
          effectiveness_json=$(echo "$effectiveness_json" | jq --arg k "$key" --argjson v "$val" '. + {($k): $v}')
        else
          effectiveness_json=$(echo "$effectiveness_json" | jq --arg k "$key" --arg v "$val" '. + {($k): $v}')
        fi
      fi
      continue
    fi

    # Top-level key: value
    current_block=""
    local key val
    key=$(echo "$line" | cut -d: -f1)
    val=$(echo "$line" | sed 's/^[^:]*: *//' | sed 's/^"//' | sed 's/"$//')

    # Handle booleans and numbers natively
    if [ "$val" = "true" ] || [ "$val" = "false" ]; then
      json=$(echo "$json" | jq --arg k "$key" --argjson v "$val" '. + {($k): $v}')
    elif echo "$val" | grep -qE '^[0-9]+$'; then
      json=$(echo "$json" | jq --arg k "$key" --argjson v "$val" '. + {($k): $v}')
    else
      json=$(echo "$json" | jq --arg k "$key" --arg v "$val" '. + {($k): $v}')
    fi
  done <<< "$yaml"

  # Apply defaults: category defaults to "strategy" if not present
  if ! echo "$json" | jq -e '.category' >/dev/null 2>&1; then
    json=$(echo "$json" | jq '. + {category: "strategy"}')
  fi

  # Merge source, overrides, and effectiveness into final JSON
  echo "$json" | jq \
    --argjson source "$source_json" \
    --argjson overrides "$overrides" \
    --argjson effectiveness "$effectiveness_json" \
    '. + {source: $source, overrides: $overrides, effectiveness: $effectiveness}'
}

# Extract the body (content after frontmatter) from a rule file.
parse_rule_body() {
  local file="$1"
  local past_frontmatter=false
  local found_start=false

  while IFS= read -r line; do
    if [ "$line" = "---" ]; then
      if [ "$found_start" = true ]; then
        past_frontmatter=true
        continue
      else
        found_start=true
        continue
      fi
    fi
    if [ "$past_frontmatter" = true ]; then
      echo "$line"
    fi
  done < "$file"
}

# Log a meta-cognition event to the append-only audit trail.
# Usage: log_meta_cognition_event "event_name" [jq --arg flags...]
log_meta_cognition_event() {
  local event="$1"
  shift

  local log_dir="${SUCCESSION_GLOBAL_DIR}/log"
  mkdir -p "$log_dir"
  jq -cn \
    --arg ts "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    --arg session "${SESSION_ID:-unknown}" \
    --arg event "$event" \
    "$@" \
    '{ts: $ts, session: $session, event: $event} + $ARGS.named' \
    >> "${log_dir}/meta-cognition.jsonl" 2>/dev/null || true
}

# Update effectiveness counters in rule file frontmatter.
# Reads events from meta-cognition.jsonl, increments counters in each rule file.
# Usage: update_effectiveness_counters <rules_dir> [<global_rules_dir>]
update_effectiveness_counters() {
  local log_file="${SUCCESSION_GLOBAL_DIR}/log/meta-cognition.jsonl"
  [ -f "$log_file" ] || return

  # Extract rule_followed and rule_violated events, group by rule_id
  local counts
  counts=$(jq -s '
    [.[] | select(.event == "rule_followed" or .event == "rule_violated")]
    | group_by(.rule_id)
    | map({
        rule_id: .[0].rule_id,
        followed: [.[] | select(.event == "rule_followed")] | length,
        violated: [.[] | select(.event == "rule_violated")] | length
      })
  ' "$log_file" 2>/dev/null) || return

  [ "$counts" = "[]" ] && return

  # For each rule with events, update its file's frontmatter
  local dirs=("$@")
  while IFS= read -r entry; do
    local rule_id followed violated
    rule_id=$(echo "$entry" | jq -r '.rule_id')
    followed=$(echo "$entry" | jq -r '.followed')
    violated=$(echo "$entry" | jq -r '.violated')

    # Find rule file in provided directories
    for dir in "${dirs[@]}"; do
      local rule_file="${dir}/${rule_id}.md"
      [ -f "$rule_file" ] || continue

      # Read current counters from frontmatter
      local current
      current=$(parse_rule_frontmatter "$rule_file")
      local cur_followed cur_violated
      cur_followed=$(echo "$current" | jq -r '.effectiveness.times_followed // 0')
      cur_violated=$(echo "$current" | jq -r '.effectiveness.times_violated // 0')

      # Update counters in file (sed replace in frontmatter)
      local new_followed=$((cur_followed + followed))
      local new_violated=$((cur_violated + violated))
      local now
      now=$(date -u +%Y-%m-%dT%H:%M:%SZ)

      # Use temp file for atomic update
      local tmp_file="${rule_file}.tmp"
      awk -v nf="$new_followed" -v nv="$new_violated" -v now="$now" '
        /^  times_followed:/ { print "  times_followed: " nf; next }
        /^  times_violated:/ { print "  times_violated: " nv; next }
        /^  last_evaluated:/ { print "  last_evaluated: " now; next }
        { print }
      ' "$rule_file" > "$tmp_file" && mv "$tmp_file" "$rule_file"
      break
    done
  done < <(echo "$counts" | jq -c '.[]')
}
