#!/usr/bin/env bash
# SOUL Framework — Initialization Script
# Usage: ./soul-init.sh [--global-only]
#
# Creates the full .soul/ directory structure in the current repo
# and merges hook registrations into .claude/settings.json.
# Also creates ~/.soul/genome/ if it doesn't exist.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(pwd)"
GLOBAL_SOUL_DIR="${HOME}/.soul"
REPO_SOUL_DIR="${REPO_DIR}/.soul"
CLAUDE_SETTINGS_DIR="${REPO_DIR}/.claude"
CLAUDE_SETTINGS_FILE="${CLAUDE_SETTINGS_DIR}/settings.json"

# Colors for output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

info() { echo -e "${BLUE}[soul]${NC} $1"; }
success() { echo -e "${GREEN}[soul]${NC} $1"; }
warn() { echo -e "${YELLOW}[soul]${NC} $1"; }

# ============================================================
# Phase 1: Global genome directory (~/.soul/genome/)
# ============================================================

info "Setting up global genome directory..."

mkdir -p "${GLOBAL_SOUL_DIR}/genome"

if [ ! -f "${GLOBAL_SOUL_DIR}/genome/base.md" ]; then
  cat > "${GLOBAL_SOUL_DIR}/genome/base.md" << 'GENOME_EOF'
# Base Genome — Universal Agent Traits

## Core Behavior
- Think step-by-step before taking action
- Read before editing; understand before modifying
- Prefer minimal, targeted changes over sweeping refactors
- When uncertain, ask rather than guess
- Never silently swallow errors — surface them clearly

## Communication
- Be direct and concise
- Show your reasoning when making non-obvious decisions
- Flag risks and tradeoffs explicitly

## Safety
- Never commit secrets, credentials, or tokens
- Never force-push without explicit confirmation
- Never delete files or branches without confirmation
- Verify destructive operations before executing

## Knowledge Management
- When you learn something important, note it for future sessions
- Distinguish between confirmed facts and working hypotheses
- Update your understanding when evidence contradicts prior beliefs
GENOME_EOF
  success "Created ${GLOBAL_SOUL_DIR}/genome/base.md"
else
  info "Global genome base.md already exists, skipping"
fi

# Exit early if --global-only flag
if [ "${1:-}" = "--global-only" ]; then
  success "Global genome setup complete."
  exit 0
fi

# ============================================================
# Phase 2: Repo soul directory (.soul/)
# ============================================================

info "Setting up repo soul directory..."

mkdir -p "${REPO_SOUL_DIR}"/{invariants,hooks,log}
mkdir -p "${REPO_DIR}/.plans"

# --- SOUL.md template ---
if [ ! -f "${REPO_SOUL_DIR}/SOUL.md" ]; then
  cat > "${REPO_SOUL_DIR}/SOUL.md" << 'SOUL_EOF'
# Soul

## Identity
<!-- Who am I? What is my purpose in this repo? Fill this in. -->

### Workflow Process
I follow a strict four-phase cycle for all non-trivial work:

1. **Document the current state** — Before changing anything, read and understand the existing code and documentation. Record findings as context in a `.plans/` file.
2. **Plan the changes** — Enter plan mode proactively. Design the approach with specific file paths, line numbers, and code snippets. Include a verification section. Get user approval before proceeding.
3. **Implement** — Execute the approved plan. Make targeted changes, verify each step.
4. **Document the outcome** — Update relevant documentation to reflect what changed and why. Plans in `.plans/` serve as the historical record.

I enter plan mode proactively for any task that touches more than a trivial fix. When in doubt, I plan first.

## Accumulated Knowledge
<!-- Facts confirmed across sessions. Updated by rolling compaction. Facts only — no behavioral rules. -->

## Current Understanding
<!-- Compact summary of codebase/task state as of last compaction. -->

## Skills

### reviewer
Reviews code changes against architectural invariants and accumulated knowledge.
Focus: correctness, invariant compliance, pattern consistency.
mode: fork

### explorer
Deep-dives into unfamiliar parts of the codebase to expand Accumulated Knowledge.
Focus: discovery, pattern recognition, knowledge compaction.
mode: fork
SOUL_EOF
  success "Created ${REPO_SOUL_DIR}/SOUL.md"
else
  info "SOUL.md already exists, skipping"
fi

# --- Invariant templates ---
if [ ! -f "${REPO_SOUL_DIR}/invariants/architecture.md" ]; then
  cat > "${REPO_SOUL_DIR}/invariants/architecture.md" << 'INV_EOF'
# Architecture Invariants

<!-- Add your project's architectural rules here. Examples: -->
- Never introduce circular dependencies between modules
- All API endpoints must have input validation
INV_EOF
  success "Created invariants/architecture.md"
fi

if [ ! -f "${REPO_SOUL_DIR}/invariants/behavior.md" ]; then
  cat > "${REPO_SOUL_DIR}/invariants/behavior.md" << 'INV_EOF'
# Behavior Invariants

<!-- Add behavioral rules the agent must follow. Examples: -->
- Never auto-commit without explicit user request
- Always read a file before editing it
- Never delete files without confirmation
- Always enter plan mode before implementing non-trivial changes (more than a single-file fix)
- Never start implementation without a plan file in .plans/ that has user approval
- After completing implementation, update relevant documentation to reflect the changes
INV_EOF
  success "Created invariants/behavior.md"
fi

# --- config.json ---
if [ ! -f "${REPO_SOUL_DIR}/config.json" ]; then
  cat > "${REPO_SOUL_DIR}/config.json" << 'CONFIG_EOF'
{
  "conscience": {
    "auditModel": "sonnet",
    "correctionModel": "sonnet",
    "auditEveryNTurns": 5,
    "alwaysAuditKeywords": ["commit", "delete", "deploy", "push", "force", "drop", "remove", "destroy"],
    "killAfterNViolations": 3
  },
  "genome": {
    "order": ["base"]
  },
  "compaction": {
    "autoCommit": true
  },
  "patterns": {
    "autoWriteInvariants": true
  },
  "preToolUse": {
    "enabled": true
  }
}
CONFIG_EOF
  success "Created config.json"
fi

# --- Hook scripts ---
# Copy hooks from the framework if available, otherwise create them
FRAMEWORK_HOOKS_DIR="${SCRIPT_DIR}/.soul/hooks"

for hook_name in session-start.sh conscience.sh compact.sh pre-tool-use.sh; do
  target="${REPO_SOUL_DIR}/hooks/${hook_name}"
  if [ ! -f "$target" ]; then
    if [ -f "${FRAMEWORK_HOOKS_DIR}/${hook_name}" ]; then
      cp "${FRAMEWORK_HOOKS_DIR}/${hook_name}" "$target"
      chmod +x "$target"
      success "Installed hook: ${hook_name}"
    else
      warn "Hook template not found: ${hook_name} — you'll need to create it manually"
    fi
  else
    info "Hook ${hook_name} already exists, skipping"
  fi
done

# ============================================================
# Phase 3: Register hooks in .claude/settings.json
# ============================================================

info "Registering hooks in .claude/settings.json..."

mkdir -p "${CLAUDE_SETTINGS_DIR}"

# Define the hooks we want to register
SOUL_HOOKS=$(cat << 'HOOKS_EOF'
{
  "hooks": {
    "SessionStart": [
      {
        "matcher": "",
        "hooks": [
          {
            "type": "command",
            "command": "\"$CLAUDE_PROJECT_DIR\"/.soul/hooks/session-start.sh"
          }
        ]
      }
    ],
    "Stop": [
      {
        "matcher": "",
        "hooks": [
          {
            "type": "command",
            "command": "\"$CLAUDE_PROJECT_DIR\"/.soul/hooks/conscience.sh"
          }
        ]
      }
    ],
    "PostCompact": [
      {
        "matcher": "",
        "hooks": [
          {
            "type": "command",
            "command": "\"$CLAUDE_PROJECT_DIR\"/.soul/hooks/compact.sh"
          }
        ]
      }
    ],
    "PreToolUse": [
      {
        "matcher": "",
        "hooks": [
          {
            "type": "command",
            "command": "\"$CLAUDE_PROJECT_DIR\"/.soul/hooks/pre-tool-use.sh"
          }
        ]
      }
    ]
  }
}
HOOKS_EOF
)

if [ -f "$CLAUDE_SETTINGS_FILE" ]; then
  # Merge soul hooks into existing settings, preserving existing hooks
  EXISTING=$(cat "$CLAUDE_SETTINGS_FILE")

  # Check if hooks key exists
  if echo "$EXISTING" | jq -e '.hooks' > /dev/null 2>&1; then
    # Merge each hook event, appending soul hooks to any existing hooks
    MERGED=$(echo "$EXISTING" | jq --argjson soul_hooks "$SOUL_HOOKS" '
      .hooks as $existing_hooks |
      ($soul_hooks.hooks | keys[]) as $event |
      if $existing_hooks[$event] then
        # Check if soul hook already registered (by command path)
        ($soul_hooks.hooks[$event][0].hooks[0].command) as $soul_cmd |
        if ($existing_hooks[$event] | any(.hooks[]?; .command == $soul_cmd)) then
          .  # Already registered, skip
        else
          .hooks[$event] += $soul_hooks.hooks[$event]
        end
      else
        .hooks[$event] = $soul_hooks.hooks[$event]
      end
    ')
    echo "$MERGED" | jq '.' > "$CLAUDE_SETTINGS_FILE"
  else
    # No hooks key yet — add it
    echo "$EXISTING" | jq --argjson soul_hooks "$SOUL_HOOKS" '. + {hooks: $soul_hooks.hooks}' > "$CLAUDE_SETTINGS_FILE"
  fi
  success "Merged soul hooks into existing .claude/settings.json"
else
  # Create new settings file
  echo "$SOUL_HOOKS" | jq '.' > "$CLAUDE_SETTINGS_FILE"
  success "Created .claude/settings.json with soul hooks"
fi

# ============================================================
# Phase 4: Summary
# ============================================================

echo ""
success "SOUL framework initialized!"
echo ""
echo "  Directory structure:"
echo "    ${GLOBAL_SOUL_DIR}/genome/base.md    — Global agent traits"
echo "    ${REPO_SOUL_DIR}/SOUL.md             — Repo soul (edit this!)"
echo "    ${REPO_SOUL_DIR}/invariants/         — Human-authored invariants"
echo "    ${REPO_SOUL_DIR}/config.json         — Conscience & compaction config"
echo "    ${REPO_SOUL_DIR}/hooks/              — Hook scripts"
echo "    ${REPO_SOUL_DIR}/log/                — Audit trail"
echo ""
echo "  Next steps:"
echo "    1. Edit .soul/SOUL.md — fill in your agent's Identity section"
echo "    2. Edit .soul/invariants/*.md — add your project's invariants"
echo "    3. Edit ~/.soul/genome/base.md — customize your global agent traits"
echo "    4. (Optional) Add language/archetype genomes to ~/.soul/genome/"
echo "    5. Start a claude session — the soul will be injected automatically"
echo ""
echo "  The conscience audits every ${BLUE}5${NC} turns (configurable in config.json)."
echo "  Audit trail is logged to .soul/log/conscience.jsonl"
