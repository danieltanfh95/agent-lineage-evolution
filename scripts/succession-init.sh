#!/usr/bin/env bash
# Succession — One-time Setup
# Creates directory structure and registers hooks in Claude Code settings.
# Usage: ./succession-init.sh [--project-only]
#
# --project-only: Only set up .succession/ in current directory, skip global install

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GLOBAL_DIR="${HOME}/.succession"
SETTINGS_FILE="${HOME}/.claude/settings.json"
PROJECT_DIR="${PWD}"

echo "=== Succession Setup ==="
echo ""

# --- Parse args ---
PROJECT_ONLY=false
for arg in "$@"; do
  case "$arg" in
    --project-only) PROJECT_ONLY=true ;;
  esac
done

# --- Step 1: Create global directory structure ---
if [ "$PROJECT_ONLY" = false ]; then
  echo "Creating global directories..."
  mkdir -p "${GLOBAL_DIR}/rules"
  mkdir -p "${GLOBAL_DIR}/skills"
  mkdir -p "${GLOBAL_DIR}/compiled"
  mkdir -p "${GLOBAL_DIR}/log"

  # Require babashka
  if ! command -v bb &>/dev/null; then
    echo "Error: babashka (bb) is required. Install with:"
    echo "  bash < <(curl -s https://raw.githubusercontent.com/babashka/babashka/master/install)"
    exit 1
  fi

  # Copy babashka source
  BB_DIR="$(cd "${SCRIPT_DIR}/../bb" 2>/dev/null && pwd)"
  if [ -d "$BB_DIR" ]; then
    cp -r "$BB_DIR" "${GLOBAL_DIR}/bb"
    echo "  ~/.succession/bb/           — babashka implementation"
  else
    echo "Error: bb/ source directory not found at ${SCRIPT_DIR}/../bb"
    exit 1
  fi

  echo "  ~/.succession/rules/        — global rules"
  echo "  ~/.succession/skills/       — global skills"
  echo ""

  # Default config
  CONFIG_FILE="${GLOBAL_DIR}/config.json"
  if [ ! -f "$CONFIG_FILE" ]; then
    cat > "$CONFIG_FILE" << 'EOF'
{
  "model": "sonnet",
  "correctionModel": "sonnet",
  "extractEveryKTokens": 80000,
  "reinjectionInterval": 10,
  "debug": false
}
EOF
    echo "Created default config at ~/.succession/config.json"
  fi
fi

# --- Step 2: Create project directory structure ---
echo "Creating project directories..."
mkdir -p "${PROJECT_DIR}/.succession/rules"
mkdir -p "${PROJECT_DIR}/.succession/skills"
mkdir -p "${PROJECT_DIR}/.succession/compiled"
mkdir -p "${PROJECT_DIR}/.succession/log"

# Add compiled/ and log/ to gitignore
GITIGNORE="${PROJECT_DIR}/.succession/.gitignore"
if [ ! -f "$GITIGNORE" ]; then
  cat > "$GITIGNORE" << 'EOF'
compiled/
log/
EOF
  echo "Created .succession/.gitignore"
fi

echo "  .succession/rules/          — project rules"
echo "  .succession/skills/         — project skills"
echo ""

# --- Step 3: Register hooks in Claude Code settings ---
if [ "$PROJECT_ONLY" = false ]; then
  echo "Registering hooks in Claude Code settings..."

  BB_SRC_DIR="${GLOBAL_DIR}/bb"

  SESSION_START_CMD="bb -cp ${BB_SRC_DIR}/src -m succession.hooks.session-start"
  PRE_TOOL_USE_CMD="cat | bb -cp ${BB_SRC_DIR}/src -m succession.hooks.pre-tool-use"
  STOP_CMD="cat | bb -cp ${BB_SRC_DIR}/src -m succession.hooks.stop"

  # Build the hooks JSON
  HOOKS_JSON=$(jq -n \
    --arg session_start "$SESSION_START_CMD" \
    --arg pre_tool_use "$PRE_TOOL_USE_CMD" \
    --arg stop "$STOP_CMD" \
    '{
      "SessionStart": [
        {
          "hooks": [
            {
              "type": "command",
              "command": $session_start
            }
          ]
        }
      ],
      "PreToolUse": [
        {
          "hooks": [
            {
              "type": "command",
              "command": $pre_tool_use
            }
          ]
        }
      ],
      "Stop": [
        {
          "hooks": [
            {
              "type": "command",
              "command": $stop
            }
          ]
        }
      ]
    }')

  # Read existing settings or create new
  if [ -f "$SETTINGS_FILE" ]; then
    # Merge hooks into existing settings (preserve other settings)
    EXISTING=$(cat "$SETTINGS_FILE")
    UPDATED=$(echo "$EXISTING" | jq --argjson hooks "$HOOKS_JSON" '.hooks = $hooks')
    echo "$UPDATED" | jq '.' > "$SETTINGS_FILE"
    echo "  Updated ${SETTINGS_FILE}"
  else
    mkdir -p "$(dirname "$SETTINGS_FILE")"
    jq -n --argjson hooks "$HOOKS_JSON" '{hooks: $hooks}' > "$SETTINGS_FILE"
    echo "  Created ${SETTINGS_FILE}"
  fi

  echo ""
  echo "Registered hooks:"
  echo "  SessionStart → ${SESSION_START_CMD}"
  echo "  PreToolUse   → ${PRE_TOOL_USE_CMD}"
  echo "  Stop         → ${STOP_CMD}"
fi

# --- Step 4: Install skill for /succession commands ---
if [ "$PROJECT_ONLY" = false ]; then
  SKILL_DIR="${HOME}/.claude/skills/succession"
  mkdir -p "$SKILL_DIR"
  if [ -f "${SCRIPT_DIR}/SKILL.md" ]; then
    cp "${SCRIPT_DIR}/SKILL.md" "${SKILL_DIR}/SKILL.md"
    echo ""
    echo "Installed /succession skill at ${SKILL_DIR}/"
  fi
fi

echo ""
echo "=== Setup Complete ==="
echo ""
echo "Next steps:"
echo "  1. Add global rules:   echo '...' > ~/.succession/rules/my-rule.md"
echo "  2. Add project rules:  echo '...' > .succession/rules/my-rule.md"
echo "  3. Start a Claude Code session — rules will be enforced automatically"
echo "  4. Use /succession show to see active rules"
echo "  5. Use /succession extract to analyze past transcripts"
