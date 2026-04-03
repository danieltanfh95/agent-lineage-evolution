#!/usr/bin/env bash
# Imprint — Shared Hook Library
# Sourced by all hook scripts.
# Provides: map_model_id(), log_imprint_event(), parse_rule_frontmatter()

IMPRINT_GLOBAL_DIR="${HOME}/.imprint"
IMPRINT_PROJECT_DIR=""  # Set by caller from CWD

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
# Usage: log_imprint_event "event_name" [jq --arg flags...]
# Requires $LOG_DIR and $SESSION_ID to be set by the caller.
log_imprint_event() {
  local event="$1"
  shift

  local log_dir="${LOG_DIR:-${IMPRINT_GLOBAL_DIR}/log}"
  mkdir -p "$log_dir"
  jq -cn \
    --arg ts "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    --arg session "${SESSION_ID:-unknown}" \
    --arg event "$event" \
    "$@" \
    '{timestamp: $ts, session: $session, event: $event} + $ARGS.named' \
    >> "${log_dir}/imprint-activity.jsonl" 2>/dev/null || true
}

# Rotate log file if it exceeds 1MB.
rotate_log_if_needed() {
  local log_file="${LOG_DIR:-${IMPRINT_GLOBAL_DIR}/log}/imprint-activity.jsonl"
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
  local in_source=false
  local in_overrides=false
  local overrides="[]"
  local source_json='{}'

  while IFS= read -r line; do
    [ -n "$line" ] || continue

    # Handle source: block
    if [ "$line" = "source:" ]; then
      in_source=true
      in_overrides=false
      continue
    fi

    # Handle overrides: block
    if [ "$line" = "overrides: []" ]; then
      overrides="[]"
      in_source=false
      in_overrides=false
      continue
    fi
    if echo "$line" | grep -q '^overrides:'; then
      in_overrides=true
      in_source=false
      overrides="[]"
      continue
    fi

    # Handle override list items
    if [ "$in_overrides" = true ] && echo "$line" | grep -q '^  - '; then
      local item
      item=$(echo "$line" | sed 's/^  - //')
      overrides=$(echo "$overrides" | jq --arg v "$item" '. + [$v]')
      continue
    fi

    # Handle source sub-fields
    if [ "$in_source" = true ] && echo "$line" | grep -q '^  '; then
      local key val
      key=$(echo "$line" | sed 's/^  //' | cut -d: -f1)
      val=$(echo "$line" | sed 's/^  [^:]*: *//' | sed 's/^"//' | sed 's/"$//')
      source_json=$(echo "$source_json" | jq --arg k "$key" --arg v "$val" '. + {($k): $v}')
      continue
    fi

    # Top-level key: value
    in_source=false
    in_overrides=false
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

  # Merge source and overrides into final JSON
  echo "$json" | jq \
    --argjson source "$source_json" \
    --argjson overrides "$overrides" \
    '. + {source: $source, overrides: $overrides}'
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
