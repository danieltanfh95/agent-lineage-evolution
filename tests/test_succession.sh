#!/usr/bin/env bash
# Succession Hook Tests — shell-level regression tests for scripts/*.sh
# Tests: lib.sh parsing, cascade resolution, PreToolUse blocking, Stop hook phases, SessionStart.
# Usage: bash tests/test_succession.sh
#
# Requirements: bash, jq
# No API calls — uses a mock claude script for all LLM interactions.

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
SCRIPTS_DIR="$REPO_ROOT/scripts"

PASS=0
FAIL=0
ORIGINAL_PATH="$PATH"
ORIGINAL_HOME="$HOME"

# ============================================================
# TEST FRAMEWORK
# ============================================================

assert_eq() {
  local label="$1" expected="$2" actual="$3"
  if [ "$expected" = "$actual" ]; then
    PASS=$((PASS + 1))
    echo "  PASS: $label"
  else
    FAIL=$((FAIL + 1))
    echo "  FAIL: $label"
    echo "    expected: $expected"
    echo "    actual:   $actual"
  fi
}

assert_contains() {
  local label="$1" haystack="$2" needle="$3"
  if echo "$haystack" | grep -q "$needle"; then
    PASS=$((PASS + 1))
    echo "  PASS: $label"
  else
    FAIL=$((FAIL + 1))
    echo "  FAIL: $label"
    echo "    expected to contain: $needle"
    echo "    actual: $(echo "$haystack" | head -c 200)"
  fi
}

assert_not_contains() {
  local label="$1" haystack="$2" needle="$3"
  if ! echo "$haystack" | grep -q "$needle"; then
    PASS=$((PASS + 1))
    echo "  PASS: $label"
  else
    FAIL=$((FAIL + 1))
    echo "  FAIL: $label"
    echo "    expected NOT to contain: $needle"
  fi
}

assert_file_exists() {
  local label="$1" path="$2"
  if [ -f "$path" ]; then
    PASS=$((PASS + 1))
    echo "  PASS: $label"
  else
    FAIL=$((FAIL + 1))
    echo "  FAIL: $label"
    echo "    file not found: $path"
  fi
}

assert_json_valid() {
  local label="$1" json="$2"
  if echo "$json" | jq empty 2>/dev/null; then
    PASS=$((PASS + 1))
    echo "  PASS: $label"
  else
    FAIL=$((FAIL + 1))
    echo "  FAIL: $label"
    echo "    invalid JSON: $(echo "$json" | head -c 200)"
  fi
}

# ============================================================
# SETUP / TEARDOWN
# ============================================================

setup() {
  TEST_DIR=$(mktemp -d)
  TEST_SESSION_ID="test-$$-$(date +%s)"

  # Isolate HOME to prevent reading/writing real ~/.succession/
  export HOME="$TEST_DIR/fakehome"
  mkdir -p "$HOME/.succession/rules"
  mkdir -p "$HOME/.succession/compiled"
  mkdir -p "$HOME/.succession/log"
  mkdir -p "$HOME/.succession/skills"

  # Create project .succession directory
  mkdir -p "$TEST_DIR/project/.succession/rules"
  mkdir -p "$TEST_DIR/project/.succession/compiled"
  mkdir -p "$TEST_DIR/project/.succession/log"
  mkdir -p "$TEST_DIR/project/.succession/skills"

  # Create mock claude binary
  mkdir -p "$TEST_DIR/mock-bin"
  cat > "$TEST_DIR/mock-bin/claude" << 'MOCK_EOF'
#!/usr/bin/env bash
# Mock claude that returns configurable responses
MODE="${MOCK_CLAUDE_MODE:-pass}"
case "$MODE" in
  pass)
    echo '{"result": "{\"rules\": []}"}'
    ;;
  correction_yes)
    echo '{"result": "YES"}'
    ;;
  correction_no)
    echo '{"result": "NO"}'
    ;;
  extract)
    echo '{"result": "{\"rules\": [{\"id\": \"test-rule\", \"enforcement\": \"mechanical\", \"type\": \"correction\", \"scope\": \"project\", \"summary\": \"Test rule from extraction\", \"evidence\": \"User said stop\", \"enforcement_directives\": [\"block_bash_pattern: rm -rf\", \"reason: Test block\"]}]}"}'
    ;;
  skill_extract)
    echo '{"result": "{\"skill_name\": \"test-skill\", \"description\": \"A test skill\", \"context\": {\"trigger\": \"when testing\", \"file_patterns\": [\"test_*.sh\"], \"keywords\": [\"test\"]}, \"steps\": [\"Step 1: Do thing\"], \"knowledge\": [\"Fact 1\"], \"rules\": [], \"summary\": \"Test skill\"}"}'
    ;;
esac
MOCK_EOF
  chmod +x "$TEST_DIR/mock-bin/claude"
  export PATH="$TEST_DIR/mock-bin:$ORIGINAL_PATH"

  # Create a mock transcript
  mkdir -p "$TEST_DIR/transcripts"
  cat > "$TEST_DIR/transcripts/test-session.jsonl" << 'TRANSCRIPT_EOF'
{"type":"human","message":{"content":"hello"}}
{"type":"assistant","message":{"content":"hi there"}}
{"type":"human","message":{"content":"no, don't do that"}}
{"type":"assistant","message":{"content":"ok, I'll stop"}}
TRANSCRIPT_EOF
}

teardown() {
  export PATH="$ORIGINAL_PATH"
  export HOME="$ORIGINAL_HOME"
  rm -rf "$TEST_DIR" 2>/dev/null || true
  # Clean up temp files
  rm -f /tmp/.succession-turns-${TEST_SESSION_ID}
  rm -f /tmp/.succession-extract-offset-${TEST_SESSION_ID}
  rm -f /tmp/.succession-correction-flag-${TEST_SESSION_ID}
}

# ============================================================
# HELPERS
# ============================================================

create_rule_file() {
  local dir="$1" id="$2" enforcement="$3" body="$4"
  cat > "${dir}/${id}.md" << EOF
---
id: ${id}
scope: global
enforcement: ${enforcement}
type: correction
source:
  session: test
  timestamp: 2026-01-01T00:00:00Z
  evidence: "test"
overrides: []
enabled: true
---

${body}
EOF
}

# ============================================================
# TEST: lib.sh — parse_rule_frontmatter
# ============================================================

test_lib_parse_frontmatter() {
  echo "=== Test: lib.sh parse_rule_frontmatter ==="
  setup

  source "$SCRIPTS_DIR/lib.sh"

  create_rule_file "$HOME/.succession/rules" "test-parse" "mechanical" "Test body"

  local result
  result=$(parse_rule_frontmatter "$HOME/.succession/rules/test-parse.md")

  assert_json_valid "frontmatter is valid JSON" "$result"
  assert_eq "id parsed" "test-parse" "$(echo "$result" | jq -r '.id')"
  assert_eq "enforcement parsed" "mechanical" "$(echo "$result" | jq -r '.enforcement')"
  assert_eq "enabled parsed" "true" "$(echo "$result" | jq -r '.enabled')"

  teardown
}

# ============================================================
# TEST: lib.sh — parse_rule_body
# ============================================================

test_lib_parse_body() {
  echo "=== Test: lib.sh parse_rule_body ==="
  setup

  source "$SCRIPTS_DIR/lib.sh"

  create_rule_file "$HOME/.succession/rules" "test-body" "advisory" "This is the rule body.

## Enforcement
- block_bash_pattern: test"

  local result
  result=$(parse_rule_body "$HOME/.succession/rules/test-body.md")

  assert_contains "body contains rule text" "$result" "This is the rule body"
  assert_contains "body contains enforcement section" "$result" "## Enforcement"

  teardown
}

# ============================================================
# TEST: succession-resolve.sh — basic compilation
# ============================================================

test_resolve_basic() {
  echo "=== Test: succession-resolve.sh basic compilation ==="
  setup

  # Create a mechanical rule
  create_rule_file "$HOME/.succession/rules" "no-force-push" "mechanical" 'Never force-push.

## Enforcement
- block_bash_pattern: "git push.*(--force|-f)"
- reason: "Force-push blocked"'

  # Create an advisory rule
  create_rule_file "$HOME/.succession/rules" "prefer-concise" "advisory" "Keep responses concise."

  # Run resolve
  "$SCRIPTS_DIR/succession-resolve.sh" "$TEST_DIR/project"

  assert_file_exists "tool-rules.json created" "$TEST_DIR/project/.succession/compiled/tool-rules.json"
  assert_file_exists "semantic-rules.md created" "$TEST_DIR/project/.succession/compiled/semantic-rules.md"
  assert_file_exists "advisory-summary.md created" "$TEST_DIR/project/.succession/compiled/advisory-summary.md"

  # Check tool-rules.json content
  local tool_rules
  tool_rules=$(cat "$TEST_DIR/project/.succession/compiled/tool-rules.json")
  assert_json_valid "tool-rules.json is valid JSON" "$tool_rules"
  assert_contains "tool-rules contains force-push pattern" "$tool_rules" "block_bash_pattern"

  # Check advisory summary
  local advisory
  advisory=$(cat "$TEST_DIR/project/.succession/compiled/advisory-summary.md")
  assert_contains "advisory contains concise rule" "$advisory" "prefer-concise"

  teardown
}

# ============================================================
# TEST: succession-resolve.sh — cascade (project overrides global)
# ============================================================

test_resolve_cascade() {
  echo "=== Test: succession-resolve.sh cascade override ==="
  setup

  # Global rule: no agents
  create_rule_file "$HOME/.succession/rules" "no-agents" "mechanical" 'Never use the Agent tool.

## Enforcement
- block_tool: Agent
- reason: "Agents blocked globally"'

  # Project rule: allow agents (overrides global)
  cat > "$TEST_DIR/project/.succession/rules/allow-agents.md" << 'EOF'
---
id: no-agents
scope: project
enforcement: advisory
type: preference
source:
  session: test
  timestamp: 2026-01-01T00:00:00Z
  evidence: "override"
overrides: []
enabled: true
---

Agents are allowed in this project.
EOF

  "$SCRIPTS_DIR/succession-resolve.sh" "$TEST_DIR/project"

  local tool_rules
  tool_rules=$(cat "$TEST_DIR/project/.succession/compiled/tool-rules.json")

  # The project rule should have overridden the global block_tool: Agent
  assert_not_contains "Agent not blocked (project override)" "$tool_rules" "block_tool"

  teardown
}

# ============================================================
# TEST: succession-resolve.sh — explicit overrides field
# ============================================================

test_resolve_explicit_override() {
  echo "=== Test: succession-resolve.sh explicit overrides ==="
  setup

  # Global rule
  create_rule_file "$HOME/.succession/rules" "strict-mode" "advisory" "Use strict mode everywhere."

  # Project rule that explicitly overrides strict-mode
  cat > "$TEST_DIR/project/.succession/rules/relax-strict.md" << 'EOF'
---
id: relax-strict
scope: project
enforcement: advisory
type: preference
source:
  session: test
  timestamp: 2026-01-01T00:00:00Z
  evidence: "test"
overrides:
  - strict-mode
enabled: true
---

Strict mode not needed here.
EOF

  "$SCRIPTS_DIR/succession-resolve.sh" "$TEST_DIR/project"

  local advisory
  advisory=$(cat "$TEST_DIR/project/.succession/compiled/advisory-summary.md")

  assert_not_contains "strict-mode overridden" "$advisory" "strict-mode"
  assert_contains "relax-strict present" "$advisory" "relax-strict"

  teardown
}

# ============================================================
# TEST: succession-resolve.sh — disabled rules filtered out
# ============================================================

test_resolve_disabled() {
  echo "=== Test: succession-resolve.sh disabled rules ==="
  setup

  cat > "$HOME/.succession/rules/disabled-rule.md" << 'EOF'
---
id: disabled-rule
scope: global
enforcement: advisory
type: correction
source:
  session: test
  timestamp: 2026-01-01T00:00:00Z
  evidence: "test"
overrides: []
enabled: false
---

This rule is disabled.
EOF

  "$SCRIPTS_DIR/succession-resolve.sh" "$TEST_DIR/project"

  local advisory
  advisory=$(cat "$TEST_DIR/project/.succession/compiled/advisory-summary.md")
  assert_not_contains "disabled rule not present" "$advisory" "disabled-rule"

  teardown
}

# ============================================================
# TEST: succession-pre-tool-use.sh — blocks matching bash pattern
# ============================================================

test_pre_tool_use_block_bash() {
  echo "=== Test: succession-pre-tool-use.sh block bash pattern ==="
  setup

  # Write compiled tool-rules.json directly
  cat > "$TEST_DIR/project/.succession/compiled/tool-rules.json" << 'EOF'
[
  {"block_bash_pattern": "git push.*(--force|-f)", "reason": "No force push", "source": "test"}
]
EOF

  local result
  result=$(echo '{"cwd":"'"$TEST_DIR/project"'","tool_name":"Bash","tool_input":{"command":"git push --force origin main"},"session_id":"test"}' \
    | "$SCRIPTS_DIR/succession-pre-tool-use.sh" 2>/dev/null)

  assert_json_valid "output is valid JSON" "$result"
  assert_contains "blocks force push" "$result" "block"
  assert_contains "includes reason" "$result" "No force push"

  teardown
}

# ============================================================
# TEST: succession-pre-tool-use.sh — allows non-matching command
# ============================================================

test_pre_tool_use_allow() {
  echo "=== Test: succession-pre-tool-use.sh allows safe commands ==="
  setup

  cat > "$TEST_DIR/project/.succession/compiled/tool-rules.json" << 'EOF'
[
  {"block_bash_pattern": "git push.*(--force|-f)", "reason": "No force push", "source": "test"}
]
EOF

  local result
  result=$(echo '{"cwd":"'"$TEST_DIR/project"'","tool_name":"Bash","tool_input":{"command":"git push origin main"},"session_id":"test"}' \
    | "$SCRIPTS_DIR/succession-pre-tool-use.sh" 2>/dev/null)

  # Should exit 0 with no output (allow)
  assert_eq "no output for allowed command" "" "$result"

  teardown
}

# ============================================================
# TEST: succession-pre-tool-use.sh — blocks tool by name
# ============================================================

test_pre_tool_use_block_tool() {
  echo "=== Test: succession-pre-tool-use.sh block tool ==="
  setup

  cat > "$TEST_DIR/project/.succession/compiled/tool-rules.json" << 'EOF'
[
  {"block_tool": "Agent", "reason": "No subagents allowed", "source": "test"}
]
EOF

  local result
  result=$(echo '{"cwd":"'"$TEST_DIR/project"'","tool_name":"Agent","tool_input":{},"session_id":"test"}' \
    | "$SCRIPTS_DIR/succession-pre-tool-use.sh" 2>/dev/null)

  assert_contains "blocks Agent tool" "$result" "block"
  assert_contains "includes reason" "$result" "No subagents"

  teardown
}

# ============================================================
# TEST: succession-pre-tool-use.sh — no rules file = allow
# ============================================================

test_pre_tool_use_no_rules() {
  echo "=== Test: succession-pre-tool-use.sh no rules = allow ==="
  setup

  # Don't create any rules file
  rm -f "$TEST_DIR/project/.succession/compiled/tool-rules.json" 2>/dev/null

  local result
  result=$(echo '{"cwd":"'"$TEST_DIR/project"'","tool_name":"Bash","tool_input":{"command":"rm -rf /"},"session_id":"test"}' \
    | "$SCRIPTS_DIR/succession-pre-tool-use.sh" 2>/dev/null)

  assert_eq "no output when no rules" "" "$result"

  teardown
}

# ============================================================
# TEST: succession-session-start.sh — injects additionalContext
# ============================================================

test_session_start_inject() {
  echo "=== Test: succession-session-start.sh injects context ==="
  setup

  # Create an advisory rule so there's something to inject
  create_rule_file "$HOME/.succession/rules" "be-concise" "advisory" "Keep responses short and direct."

  local result
  result=$(echo '{"cwd":"'"$TEST_DIR/project"'","session_id":"'"$TEST_SESSION_ID"'"}' \
    | "$SCRIPTS_DIR/succession-session-start.sh" 2>/dev/null)

  assert_json_valid "output is valid JSON" "$result"
  assert_contains "includes additionalContext" "$result" "additionalContext"
  assert_contains "includes rule content" "$result" "ACTIVE RULES"

  teardown
}

# ============================================================
# TEST: succession-session-start.sh — no .succession = silent exit
# ============================================================

test_session_start_no_succession() {
  echo "=== Test: succession-session-start.sh no .succession = exit 0 ==="
  setup

  # Use a directory without .succession/
  local bare_dir="$TEST_DIR/bare"
  mkdir -p "$bare_dir"

  # Also clear global rules
  rm -rf "$HOME/.succession/rules"/*

  local result
  result=$(echo '{"cwd":"'"$bare_dir"'","session_id":"'"$TEST_SESSION_ID"'"}' \
    | "$SCRIPTS_DIR/succession-session-start.sh" 2>/dev/null)

  assert_eq "no output for bare directory" "" "$result"

  teardown
}

# ============================================================
# TEST: succession-stop.sh — correction detection tier 1
# ============================================================

test_stop_correction_tier1() {
  echo "=== Test: succession-stop.sh correction tier 1 detection ==="
  setup

  export MOCK_CLAUDE_MODE="correction_yes"

  local result
  result=$(echo '{"cwd":"'"$TEST_DIR/project"'","session_id":"'"$TEST_SESSION_ID"'","transcript_path":"'"$TEST_DIR/transcripts/test-session.jsonl"'"}' \
    | "$SCRIPTS_DIR/succession-stop.sh" 2>/dev/null)

  # The correction flag should have been set
  assert_file_exists "correction flag created" "/tmp/.succession-correction-flag-${TEST_SESSION_ID}"

  teardown
}

# ============================================================
# TEST: succession-stop.sh — re-injection interval
# ============================================================

test_stop_reinjection() {
  echo "=== Test: succession-stop.sh advisory re-injection ==="
  setup

  export MOCK_CLAUDE_MODE="correction_no"

  # Create advisory summary
  echo "# Active Rules
- be-concise: Keep responses short" > "$TEST_DIR/project/.succession/compiled/advisory-summary.md"

  # Set turn count to just before reinjection interval (default 10)
  echo "9" > "/tmp/.succession-turns-${TEST_SESSION_ID}"

  # Create a transcript without correction keywords
  cat > "$TEST_DIR/transcripts/clean-session.jsonl" << 'CLEAN_EOF'
{"type":"human","message":{"content":"please help me with this code"}}
{"type":"assistant","message":{"content":"sure, here's the code"}}
CLEAN_EOF

  local result
  result=$(echo '{"cwd":"'"$TEST_DIR/project"'","session_id":"'"$TEST_SESSION_ID"'","transcript_path":"'"$TEST_DIR/transcripts/clean-session.jsonl"'"}' \
    | "$SCRIPTS_DIR/succession-stop.sh" 2>/dev/null)

  assert_contains "re-injects advisory rules at interval" "$result" "Active Rules"

  teardown
}

# ============================================================
# TEST: map_model_id
# ============================================================

test_model_mapping() {
  echo "=== Test: lib.sh model mapping ==="
  setup

  source "$SCRIPTS_DIR/lib.sh"

  assert_eq "sonnet maps correctly" "claude-sonnet-4-6" "$(map_model_id sonnet)"
  assert_eq "haiku maps correctly" "claude-haiku-4-5-20251001" "$(map_model_id haiku)"
  assert_eq "opus maps correctly" "claude-opus-4-6" "$(map_model_id opus)"
  assert_eq "unknown defaults to sonnet" "claude-sonnet-4-6" "$(map_model_id unknown)"

  teardown
}

# ============================================================
# RUN ALL TESTS
# ============================================================

echo ""
echo "=============================="
echo "  Succession Hook Tests"
echo "=============================="
echo ""

test_model_mapping
test_lib_parse_frontmatter
test_lib_parse_body
test_resolve_basic
test_resolve_cascade
test_resolve_explicit_override
test_resolve_disabled
test_pre_tool_use_block_bash
test_pre_tool_use_allow
test_pre_tool_use_block_tool
test_pre_tool_use_no_rules
test_session_start_inject
test_session_start_no_succession
test_stop_correction_tier1
test_stop_reinjection

echo ""
echo "=============================="
echo "  Results: ${PASS} passed, ${FAIL} failed"
echo "=============================="

if [ "$FAIL" -gt 0 ]; then
  exit 1
fi
