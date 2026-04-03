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

  # Copy scripts to global location
  mkdir -p "${GLOBAL_DIR}/scripts"
  cp "${SCRIPT_DIR}/lib.sh" "${GLOBAL_DIR}/scripts/"
  cp "${SCRIPT_DIR}/succession-resolve.sh" "${GLOBAL_DIR}/scripts/"
  cp "${SCRIPT_DIR}/succession-pre-tool-use.sh" "${GLOBAL_DIR}/scripts/"
  cp "${SCRIPT_DIR}/succession-stop.sh" "${GLOBAL_DIR}/scripts/"
  cp "${SCRIPT_DIR}/succession-session-start.sh" "${GLOBAL_DIR}/scripts/"
  chmod +x "${GLOBAL_DIR}/scripts"/*.sh

  echo "  ~/.succession/rules/        — global rules"
  echo "  ~/.succession/skills/       — global skills"
  echo "  ~/.succession/scripts/      — hook scripts"
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

  HOOKS_SCRIPT_DIR="${GLOBAL_DIR}/scripts"

  # Build the hooks JSON
  HOOKS_JSON=$(jq -n \
    --arg session_start "${HOOKS_SCRIPT_DIR}/succession-session-start.sh" \
    --arg pre_tool_use "${HOOKS_SCRIPT_DIR}/succession-pre-tool-use.sh" \
    --arg stop "${HOOKS_SCRIPT_DIR}/succession-stop.sh" \
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
              "command": ("cat | " + $pre_tool_use)
            }
          ]
        }
      ],
      "Stop": [
        {
          "hooks": [
            {
              "type": "command",
              "command": ("cat | " + $stop)
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
  echo "  SessionStart → succession-session-start.sh (cascade resolve + inject)"
  echo "  PreToolUse   → succession-pre-tool-use.sh  (mechanical enforcement)"
  echo "  Stop         → succession-stop.sh          (correction detection + extraction + re-injection)"
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
