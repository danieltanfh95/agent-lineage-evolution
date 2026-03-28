#!/bin/bash
# SOUL Status Line — shows context usage with compaction suggestion
# Receives JSON on stdin from Claude Code with context_window, model, cwd, etc.

input=$(cat)

# Read context usage
PCT=$(echo "$input" | jq -r '.context_window.used_percentage // 0' | cut -d. -f1)
MODEL=$(echo "$input" | jq -r '.model.display_name // "Claude"')
CWD=$(echo "$input" | jq -r '.cwd // ""')

# Check if SOUL is configured
SOUL_FILE="${CWD}/.soul/SOUL.md"
if [ ! -f "$SOUL_FILE" ]; then
  echo "[$MODEL] SOUL: not configured (/soul setup)"
  exit 0
fi

# SOUL memory size
MEM_SIZE=$(wc -c < "$SOUL_FILE" 2>/dev/null | tr -d ' ')

# Read threshold from config (default: 15%)
CONFIG_FILE="${CWD}/.soul/config.json"
if [ -f "$CONFIG_FILE" ]; then
  WARN_PCT=$(jq -r '.compaction.suggestAtPercent // 15' "$CONFIG_FILE" 2>/dev/null)
else
  WARN_PCT=15
fi
CRIT_PCT=$((WARN_PCT + 5))

# Color based on context usage
GREEN='\033[32m'
YELLOW='\033[33m'
RED='\033[31m'
RESET='\033[0m'

if [ "$PCT" -ge "$CRIT_PCT" ]; then
  COLOR="$RED"
  HINT=" /compact recommended"
elif [ "$PCT" -ge "$WARN_PCT" ]; then
  COLOR="$YELLOW"
  HINT=" consider /compact"
else
  COLOR="$GREEN"
  HINT=""
fi

echo -e "[$MODEL] ${COLOR}ctx:${PCT}%${RESET} | soul:${MEM_SIZE}c${HINT}"
