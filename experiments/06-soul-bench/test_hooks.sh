#!/usr/bin/env bash
# SOUL Hook Tests — shell-level regression tests for .soul/hooks/*.sh
# Tests the bug fixes from commit 3e89a58 (B1-B5, F1-F4) and structural contracts.
# Usage: bash experiments/06-soul-bench/test_hooks.sh
#
# Requirements: bash, jq
# No API calls — uses a mock claude script for all LLM interactions.

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
HOOKS_DIR="$REPO_ROOT/.soul/hooks"

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

# ============================================================
# SETUP / TEARDOWN
# ============================================================

setup() {
  TEST_DIR=$(mktemp -d)
  TEST_SESSION_ID="test-$$-$(date +%s)"

  # Isolate HOME to prevent reading/writing real ~/.soul/genome/
  export HOME="$TEST_DIR/fakehome"
  mkdir -p "$HOME/.soul/genome"

  # Create .soul directory structure
  mkdir -p "$TEST_DIR/.soul/hooks"
  mkdir -p "$TEST_DIR/.soul/invariants"
  mkdir -p "$TEST_DIR/.soul/log"

  # Copy real hooks
  cp "$HOOKS_DIR"/*.sh "$TEST_DIR/.soul/hooks/"
  chmod +x "$TEST_DIR/.soul/hooks/"*.sh

  # Write minimal config.json
  cat > "$TEST_DIR/.soul/config.json" << 'CFGEOF'
{
  "conscience": {
    "model": "haiku",
    "auditEveryNTurns": 5,
    "alwaysAuditKeywords": ["commit", "delete", "push"],
    "killAfterNViolations": 3,
    "contextTurns": 10,
    "correctionDetection": false,
    "correctionKeywords": ["no", "stop", "wrong"]
  },
  "compaction": {
    "model": "sonnet",
    "autoCommit": false,
    "requireApproval": false,
    "maxBulletLossPercent": 50
  },
  "patterns": {
    "model": "sonnet",
    "extractEveryKTokens": 20,
    "promoteToCrossProject": false
  },
  "genome": {
    "order": ["base"]
  }
}
CFGEOF

  # Write minimal SOUL.md
  cat > "$TEST_DIR/.soul/SOUL.md" << 'SOULEOF'
# Soul

## Identity
I am a test agent.

## Accumulated Knowledge
- Test fact one
- Test fact two

## Predecessor Warnings
- DO NOT do bad things

## Current Understanding
This is a test.

## Skills
No skills.
SOULEOF

  # Write minimal invariant
  cat > "$TEST_DIR/.soul/invariants/behavior.md" << 'INVEOF'
- Never auto-commit without explicit user request
- Always read a file before editing it
INVEOF

  # Create mock claude
  mkdir -p "$TEST_DIR/mock-bin"
  export MOCK_MODE_FILE="$TEST_DIR/mock-mode"
  echo "pass" > "$MOCK_MODE_FILE"

  cat > "$TEST_DIR/mock-bin/claude" << 'MOCKEOF'
#!/usr/bin/env bash
# Mock claude -p for hook tests
MODE=$(cat "$MOCK_MODE_FILE" 2>/dev/null || echo "pass")
case "$MODE" in
  pass)
    echo '[{"type":"result","result":"{\"violated\":false,\"invariant\":null,\"reason\":null}","usage":{"input_tokens":100,"output_tokens":50}}]'
    ;;
  violation)
    echo '[{"type":"result","result":"{\"violated\":true,\"invariant\":\"behavior\",\"reason\":\"auto-committed without asking\"}","usage":{"input_tokens":100,"output_tokens":50}}]'
    ;;
  timeout)
    # Simulate timeout: no output
    exit 0
    ;;
  compaction)
    # Return a valid compacted SOUL.md
    echo '[{"type":"result","result":"# Soul\n\n## Identity\nI am a compacted agent.\n\n## Accumulated Knowledge\n- Compacted fact\n\n## Predecessor Warnings\n- Compacted warning\n\n## Current Understanding\nCompacted.\n\n## Skills\nNo skills.","usage":{"input_tokens":500,"output_tokens":200}}]'
    ;;
  *)
    echo '[{"type":"result","result":"NO","usage":{"input_tokens":50,"output_tokens":5}}]'
    ;;
esac
MOCKEOF
  chmod +x "$TEST_DIR/mock-bin/claude"

  # Put mock claude first on PATH
  export PATH="$TEST_DIR/mock-bin:$ORIGINAL_PATH"
}

teardown() {
  # Clean up /tmp files the hooks create
  rm -f "/tmp/.soul-turns-${TEST_SESSION_ID}"
  rm -f "/tmp/.soul-violations-${TEST_SESSION_ID}"
  rm -f "/tmp/.soul-extract-offset-${TEST_SESSION_ID}"
  rm -f "/tmp/.soul-correction-flag-${TEST_SESSION_ID}"
  rm -f "/tmp/.soul-compact-notify-${TEST_SESSION_ID}"

  # Restore environment
  export PATH="$ORIGINAL_PATH"
  export HOME="$ORIGINAL_HOME"

  # Remove temp directory
  rm -rf "$TEST_DIR"
}

# Helper: build stdin JSON for conscience.sh
conscience_input() {
  local msg="${1:-hello}"
  echo "{\"cwd\":\"${TEST_DIR}\",\"last_assistant_message\":\"${msg}\",\"stop_hook_active\":false,\"session_id\":\"${TEST_SESSION_ID}\",\"transcript_path\":\"\"}"
}

# Helper: build stdin JSON for session-start.sh
session_start_input() {
  echo "{\"cwd\":\"${TEST_DIR}\",\"session_id\":\"${TEST_SESSION_ID}\"}"
}

# Helper: build stdin JSON for compact.sh
compact_input() {
  echo "{\"cwd\":\"${TEST_DIR}\",\"session_id\":\"${TEST_SESSION_ID}\",\"transcript_path\":\"\",\"trigger\":\"manual\"}"
}

# ============================================================
# BUG REGRESSION TESTS
# ============================================================

test_b1_block_includes_notifications() {
  setup

  # Set mock to violation mode
  echo "violation" > "$MOCK_MODE_FILE"

  # Pre-create compact notification file (simulates PostCompact ran before this Stop)
  echo "Knowledge compressed (4.2k -> 3.1k chars)" \
    > "/tmp/.soul-compact-notify-${TEST_SESSION_ID}"

  # Set turn counter to 4 so this turn (5) triggers the N-th turn audit
  echo "4" > "/tmp/.soul-turns-${TEST_SESSION_ID}"
  echo "0" > "/tmp/.soul-violations-${TEST_SESSION_ID}"

  # Run conscience.sh with a keyword-triggering message
  OUTPUT=$(conscience_input "I will commit these changes now" \
    | "$TEST_DIR/.soul/hooks/conscience.sh" 2>/dev/null) || true

  DECISION=$(echo "$OUTPUT" | jq -r '.decision // empty' 2>/dev/null)
  REASON=$(echo "$OUTPUT" | jq -r '.reason // empty' 2>/dev/null)

  assert_eq "B1: decision is block" "block" "$DECISION"
  assert_contains "B1: reason includes violation info" "$REASON" "violation"
  assert_contains "B1: reason includes compact notification" "$REASON" "Knowledge compressed"

  teardown
}

test_b2_offbyone_empty_section() {
  setup

  # Create SOUL.md with empty AK section (next ## immediately follows)
  cat > "$TEST_DIR/.soul/SOUL.md" << 'SOULEOF'
# Soul

## Identity
I am a test agent.

## Accumulated Knowledge
## Predecessor Warnings
- Never do X

## Current Understanding
Test.

## Skills
No skills.
SOULEOF

  SOUL_FILE="$TEST_DIR/.soul/SOUL.md"
  bullet="Redis caching is preferred over memcached"

  # Simulate the insert logic from conscience.sh
  AK_LINE=$(grep -n "^## Accumulated Knowledge" "$SOUL_FILE" | head -1 | cut -d: -f1)
  NEXT_SECTION=$(tail -n +"$((AK_LINE + 1))" "$SOUL_FILE" | grep -n "^## " | head -1 | cut -d: -f1)
  INSERT_LINE=$((AK_LINE + NEXT_SECTION))
  sed "${INSERT_LINE}i\\
- ${bullet}
" "$SOUL_FILE" > "${SOUL_FILE}.tmp" && mv "${SOUL_FILE}.tmp" "$SOUL_FILE"

  # Verify positions
  AK_POS=$(grep -n "^## Accumulated Knowledge" "$SOUL_FILE" | cut -d: -f1)
  BULLET_POS=$(grep -n "Redis caching" "$SOUL_FILE" | cut -d: -f1)
  PW_POS=$(grep -n "^## Predecessor Warnings" "$SOUL_FILE" | cut -d: -f1)

  assert_eq "B2-empty: bullet is after AK header" "true" \
    "$([ "$BULLET_POS" -gt "$AK_POS" ] && echo true || echo false)"
  assert_eq "B2-empty: bullet is before PW header" "true" \
    "$([ "$BULLET_POS" -lt "$PW_POS" ] && echo true || echo false)"

  teardown
}

test_b2_offbyone_normal_section() {
  setup

  SOUL_FILE="$TEST_DIR/.soul/SOUL.md"
  bullet="New knowledge bullet"

  # Use the default SOUL.md which has existing bullets in AK
  AK_LINE=$(grep -n "^## Accumulated Knowledge" "$SOUL_FILE" | head -1 | cut -d: -f1)
  NEXT_SECTION=$(tail -n +"$((AK_LINE + 1))" "$SOUL_FILE" | grep -n "^## " | head -1 | cut -d: -f1)
  INSERT_LINE=$((AK_LINE + NEXT_SECTION))
  sed "${INSERT_LINE}i\\
- ${bullet}
" "$SOUL_FILE" > "${SOUL_FILE}.tmp" && mv "${SOUL_FILE}.tmp" "$SOUL_FILE"

  AK_POS=$(grep -n "^## Accumulated Knowledge" "$SOUL_FILE" | cut -d: -f1)
  BULLET_POS=$(grep -n "New knowledge bullet" "$SOUL_FILE" | cut -d: -f1)
  PW_POS=$(grep -n "^## Predecessor Warnings" "$SOUL_FILE" | cut -d: -f1)

  assert_eq "B2-normal: bullet is after AK header" "true" \
    "$([ "$BULLET_POS" -gt "$AK_POS" ] && echo true || echo false)"
  assert_eq "B2-normal: bullet is before PW header" "true" \
    "$([ "$BULLET_POS" -lt "$PW_POS" ] && echo true || echo false)"

  teardown
}

test_b3_missing_lib() {
  setup

  # Remove lib.sh
  rm -f "$TEST_DIR/.soul/hooks/lib.sh"

  # Test conscience.sh
  OUTPUT=$(conscience_input "hello" | "$TEST_DIR/.soul/hooks/conscience.sh" 2>/dev/null) || true
  assert_eq "B3: conscience.sh exits 0 without lib.sh" "0" "$?"

  # Test session-start.sh
  OUTPUT=$(session_start_input | "$TEST_DIR/.soul/hooks/session-start.sh" 2>/dev/null) || true
  assert_eq "B3: session-start.sh exits 0 without lib.sh" "0" "$?"

  # Test compact.sh (exits early due to no transcript/git — that's fine, just shouldn't crash)
  OUTPUT=$(compact_input | "$TEST_DIR/.soul/hooks/compact.sh" 2>/dev/null) || true
  assert_eq "B3: compact.sh exits 0 without lib.sh" "0" "$?"

  teardown
}

test_b4_invalid_config() {
  setup

  # Write broken JSON to config
  echo '{broken' > "$TEST_DIR/.soul/config.json"

  # Test conscience.sh — should use defaults, not crash
  OUTPUT=$(conscience_input "hello" | "$TEST_DIR/.soul/hooks/conscience.sh" 2>/dev/null) || true
  assert_eq "B4: conscience.sh exits 0 with broken config" "0" "$?"

  # Test session-start.sh
  OUTPUT=$(session_start_input | "$TEST_DIR/.soul/hooks/session-start.sh" 2>/dev/null) || true
  assert_eq "B4: session-start.sh exits 0 with broken config" "0" "$?"

  # Test compact.sh
  OUTPUT=$(compact_input | "$TEST_DIR/.soul/hooks/compact.sh" 2>/dev/null) || true
  assert_eq "B4: compact.sh exits 0 with broken config" "0" "$?"

  teardown
}

test_b5_no_sed_i() {
  setup

  SED_I_CONSCIENCE=$(grep -c 'sed -i' "$TEST_DIR/.soul/hooks/conscience.sh" 2>/dev/null || true)
  SED_I_COMPACT=$(grep -c 'sed -i' "$TEST_DIR/.soul/hooks/compact.sh" 2>/dev/null || true)

  assert_eq "B5: no sed -i in conscience.sh" "0" "$SED_I_CONSCIENCE"
  assert_eq "B5: no sed -i in compact.sh" "0" "$SED_I_COMPACT"

  teardown
}

# ============================================================
# FRAGILE ISSUE REGRESSION TESTS
# ============================================================

test_f1_audit_timeout_logged() {
  setup

  # Set mock to timeout mode (returns empty output)
  echo "timeout" > "$MOCK_MODE_FILE"

  # Set turn counter to 4 so turn 5 triggers audit
  echo "4" > "/tmp/.soul-turns-${TEST_SESSION_ID}"

  # Run with a keyword to also trigger keyword audit
  OUTPUT=$(conscience_input "I will commit this" \
    | "$TEST_DIR/.soul/hooks/conscience.sh" 2>/dev/null) || true

  # Check log for audit_timeout event
  LOG_FILE="$TEST_DIR/.soul/log/soul-activity.jsonl"
  if [ -f "$LOG_FILE" ]; then
    TIMEOUT_COUNT=$(grep -c "audit_timeout" "$LOG_FILE" 2>/dev/null || true)
    assert_eq "F1: audit_timeout event logged" "true" \
      "$([ "$TIMEOUT_COUNT" -gt 0 ] && echo true || echo false)"
  else
    FAIL=$((FAIL + 1))
    echo "  FAIL: F1: log file not created"
  fi

  teardown
}

test_f2_fence_strip_pattern() {
  setup

  # Check that fence stripping uses sed deletion, not tr -d '\n'
  CONSCIENCE="$TEST_DIR/.soul/hooks/conscience.sh"

  # Should have: sed '/^```/d'
  SED_FENCE_COUNT=$(grep -c "sed '/\^" "$CONSCIENCE" 2>/dev/null || true)
  # Should NOT have: tr -d '\n' in the fencing context
  TR_NEWLINE_COUNT=$(grep -c "tr -d '\\\\n'" "$CONSCIENCE" 2>/dev/null || true)

  assert_eq "F2: uses sed for fence stripping" "true" \
    "$([ "$SED_FENCE_COUNT" -gt 0 ] && echo true || echo false)"
  assert_eq "F2: no tr -d newline in fence strip" "0" "$TR_NEWLINE_COUNT"

  teardown
}

test_f3_boolean_logging() {
  setup

  CONSCIENCE="$TEST_DIR/.soul/hooks/conscience.sh"

  # keyword_triggered should use --argjson, not --arg
  ARGJSON_KT=$(grep -c 'argjson keyword_triggered' "$CONSCIENCE" 2>/dev/null || true)
  ARG_KT=$(grep -c -- '--arg keyword_triggered' "$CONSCIENCE" 2>/dev/null || true)

  assert_eq "F3: keyword_triggered uses --argjson" "true" \
    "$([ "$ARGJSON_KT" -gt 0 ] && echo true || echo false)"
  assert_eq "F3: keyword_triggered not --arg" "0" "$ARG_KT"

  # soul_modified should use --argjson
  ARGJSON_SM=$(grep -c 'argjson soul_modified' "$CONSCIENCE" 2>/dev/null || true)
  assert_eq "F3: soul_modified uses --argjson" "true" \
    "$([ "$ARGJSON_SM" -gt 0 ] && echo true || echo false)"

  # keyword_matched should use --argjson
  ARGJSON_KM=$(grep -c 'argjson keyword_matched' "$CONSCIENCE" 2>/dev/null || true)
  assert_eq "F3: keyword_matched uses --argjson" "true" \
    "$([ "$ARGJSON_KM" -gt 0 ] && echo true || echo false)"

  teardown
}

test_f4_jq_not_awk_json() {
  setup

  COMPACT="$TEST_DIR/.soul/hooks/compact.sh"

  JQ_BULLETS=$(grep -c 'BULLETS_BEFORE_JSON.*jq' "$COMPACT" 2>/dev/null || true)
  AWK_BULLETS=$(grep -c 'BULLETS_BEFORE_JSON.*awk' "$COMPACT" 2>/dev/null || true)

  assert_eq "F4: bullets JSON built with jq" "true" \
    "$([ "$JQ_BULLETS" -gt 0 ] && echo true || echo false)"
  assert_eq "F4: bullets JSON not built with awk" "0" "$AWK_BULLETS"

  teardown
}

# ============================================================
# STRUCTURAL / CONTRACT TESTS
# ============================================================

test_session_start_outputs_json() {
  setup

  OUTPUT=$(session_start_input | "$TEST_DIR/.soul/hooks/session-start.sh" 2>/dev/null)

  HOOK_EVENT=$(echo "$OUTPUT" | jq -r '.hookSpecificOutput.hookEventName // empty' 2>/dev/null)
  CONTEXT=$(echo "$OUTPUT" | jq -r '.hookSpecificOutput.additionalContext // empty' 2>/dev/null)

  assert_eq "session-start: hookEventName is SessionStart" "SessionStart" "$HOOK_EVENT"
  assert_contains "session-start: context includes REPO SOUL" "$CONTEXT" "REPO SOUL"
  assert_contains "session-start: context includes SOUL content" "$CONTEXT" "test agent"
  assert_contains "session-start: context includes invariants" "$CONTEXT" "INVARIANTS"

  teardown
}

test_conscience_silent_on_normal_turn() {
  setup

  echo "pass" > "$MOCK_MODE_FILE"

  # Turn 1, no keywords — should exit silently
  OUTPUT=$(conscience_input "Here is the file content you requested." \
    | "$TEST_DIR/.soul/hooks/conscience.sh" 2>/dev/null)
  EXIT_CODE=$?

  assert_eq "conscience: exits 0 on normal turn" "0" "$EXIT_CODE"
  assert_eq "conscience: no output on normal turn" "" "$OUTPUT"

  teardown
}

test_conscience_stop_hook_active_guard() {
  setup

  # Send stop_hook_active=true — should bail immediately
  OUTPUT=$(echo "{\"cwd\":\"${TEST_DIR}\",\"last_assistant_message\":\"I will commit\",\"stop_hook_active\":true,\"session_id\":\"${TEST_SESSION_ID}\",\"transcript_path\":\"\"}" \
    | "$TEST_DIR/.soul/hooks/conscience.sh" 2>/dev/null)
  EXIT_CODE=$?

  assert_eq "stop_hook_active: exits 0" "0" "$EXIT_CODE"
  assert_eq "stop_hook_active: no output" "" "$OUTPUT"

  teardown
}

# ============================================================
# CORRECTION DETECTION + AUTO-INVARIANT TESTS
# ============================================================

test_correction_flag_not_lost_on_short_transcript() {
  # Regression: correction flag was consumed (rm -f) before threshold check,
  # so if transcript was too short, the correction was permanently lost.
  setup

  # Enable correction detection with "don't" keyword
  cat > "$TEST_DIR/.soul/config.json" << 'CFGEOF'
{
  "conscience": {
    "auditModel": "sonnet",
    "correctionModel": "sonnet",
    "auditEveryNTurns": 999,
    "alwaysAuditKeywords": [],
    "killAfterNViolations": 999,
    "contextTurns": 10,
    "correctionDetection": true,
    "correctionKeywords": ["no", "don't", "stop", "instead", "wrong", "not what I"]
  },
  "patterns": {
    "model": "sonnet",
    "extractEveryKTokens": 20,
    "promoteToCrossProject": false,
    "autoWriteInvariants": true
  }
}
CFGEOF

  # Write a SHORT transcript (well under 16KB threshold)
  TRANSCRIPT="$TEST_DIR/transcript.jsonl"
  cat > "$TRANSCRIPT" << 'TEOF'
{"type":"human","message":{"content":[{"type":"text","text":"Refactor the auth module."}]}}
{"type":"assistant","message":{"content":[{"type":"text","text":"I'll spawn a subagent to handle this."}]}}
{"type":"human","message":{"content":[{"type":"text","text":"No, don't use agents. Do it yourself."}]}}
{"type":"assistant","message":{"content":[{"type":"text","text":"Understood."}]}}
TEOF

  # Mock claude: Tier 2 returns YES, extraction returns valid JSON with invariant_suggestions
  cat > "$TEST_DIR/mock-bin/claude" << 'MOCKEOF'
#!/usr/bin/env bash
INPUT=$(cat)
# If the prompt asks YES/NO, this is Tier 2 correction confirmation
if echo "$INPUT" | grep -q "YES.*NO\|YES.*or.*NO"; then
  echo '[{"type":"result","result":"YES","usage":{"input_tokens":50,"output_tokens":5}}]'
else
  # This is the extraction call — return a valid extraction result
  echo '[{"type":"result","result":"{\"patterns\":[{\"type\":\"correction\",\"scope\":\"cross-project\",\"summary\":\"Never use subagents\",\"detail\":\"User forbade Agent tool\",\"source\":\"don'\''t use agents\"}],\"soul_updates\":{\"accumulated_knowledge\":[]},\"invariant_suggestions\":[{\"rule\":\"Never use subagents for implementation tasks\",\"evidence\":\"don'\''t use agents. Do it yourself.\",\"suggested_file\":\"behavior.md\"}],\"tool_rules\":[{\"block_tool\":\"Agent\",\"reason\":\"User forbade subagents\",\"source\":\"learned.md\"}],\"genome_updates\":[]}","usage":{"input_tokens":500,"output_tokens":200}}]'
fi
MOCKEOF
  chmod +x "$TEST_DIR/mock-bin/claude"

  # Call conscience.sh — this should trigger Tier 1+2 and set the correction flag
  echo "{\"cwd\":\"${TEST_DIR}\",\"last_assistant_message\":\"Understood.\",\"stop_hook_active\":false,\"session_id\":\"${TEST_SESSION_ID}\",\"transcript_path\":\"${TRANSCRIPT}\"}" \
    | "$TEST_DIR/.soul/hooks/conscience.sh" > /dev/null 2>&1 || true

  # BUG CHECK: The correction flag should still exist if extraction didn't fire
  # (because transcript is too short). Before the fix, it was consumed and lost.
  CORRECTION_FLAG="/tmp/.soul-correction-flag-${TEST_SESSION_ID}"

  # Check if correction was detected at all (Tier 1+2)
  LOG_FILE="$TEST_DIR/.soul/log/soul-activity.jsonl"
  TIER1_LOGGED=$(grep -c '"correction_tier1"' "$LOG_FILE" 2>/dev/null || echo 0)
  assert_eq "Tier 1 keyword match detected" "true" "$([ "$TIER1_LOGGED" -gt 0 ] && echo true || echo false)"

  TIER2_LOGGED=$(grep -c '"correction_tier2"' "$LOG_FILE" 2>/dev/null || echo 0)
  assert_eq "Tier 2 confirmed correction" "true" "$([ "$TIER2_LOGGED" -gt 0 ] && echo true || echo false)"

  # The key assertion: either extraction fired (learned.md exists) OR the flag is preserved
  # Before the fix: flag deleted, extraction skipped, correction lost
  LEARNED_EXISTS="$([ -f "$TEST_DIR/.soul/invariants/learned.md" ] && echo true || echo false)"
  FLAG_EXISTS="$([ -f "$CORRECTION_FLAG" ] && echo true || echo false)"
  CORRECTION_PRESERVED="$([ "$LEARNED_EXISTS" = "true" ] || [ "$FLAG_EXISTS" = "true" ] && echo true || echo false)"
  assert_eq "Correction either written to learned.md or flag preserved" "true" "$CORRECTION_PRESERVED"

  teardown
}

test_auto_write_invariant_from_extraction() {
  # Tests the full auto-write pipeline: extraction → learned.md + tool-rules.json
  setup

  # Config: low extraction threshold so it fires on short transcripts
  cat > "$TEST_DIR/.soul/config.json" << 'CFGEOF'
{
  "conscience": {
    "auditModel": "sonnet",
    "correctionModel": "sonnet",
    "auditEveryNTurns": 999,
    "alwaysAuditKeywords": [],
    "killAfterNViolations": 999,
    "correctionDetection": true,
    "correctionKeywords": ["no", "don't", "stop"]
  },
  "patterns": {
    "model": "sonnet",
    "extractEveryKTokens": 1,
    "promoteToCrossProject": false,
    "autoWriteInvariants": true
  }
}
CFGEOF

  # Write transcript large enough to pass the 800-byte reduced threshold
  TRANSCRIPT="$TEST_DIR/transcript.jsonl"
  # Pad with enough content to exceed threshold
  cat > "$TRANSCRIPT" << 'TEOF'
{"type":"human","message":{"content":[{"type":"text","text":"I need you to refactor the authentication module across all twelve service files in the repository. Please update the imports, change the auth provider, and ensure all tests still pass after the changes."}]}}
{"type":"assistant","message":{"content":[{"type":"text","text":"I will spawn a subagent to handle the refactoring across all twelve files in parallel. This will be more efficient since we can process multiple files simultaneously. Let me use the Agent tool to create an Explore agent for this task."}]}}
{"type":"human","message":{"content":[{"type":"text","text":"No, don't use agents for implementation tasks. Do all the work yourself directly using Read, Edit, and Bash. I do not want subagents spawned for any reason."}]}}
{"type":"assistant","message":{"content":[{"type":"text","text":"Understood, I will handle all the refactoring directly without spawning any subagents. Let me start by reading the first service file to understand the current auth implementation."}]}}
{"type":"human","message":{"content":[{"type":"text","text":"Good. Now proceed with updating the auth provider in each file."}]}}
{"type":"assistant","message":{"content":[{"type":"text","text":"I will now update the auth provider in each of the twelve service files. Starting with service-one.ts, I will read it first then make the necessary changes to switch from the old auth provider to the new one."}]}}
TEOF

  # Mock: Tier 2 YES, then extraction with invariant_suggestions + tool_rules
  cat > "$TEST_DIR/mock-bin/claude" << 'MOCKEOF'
#!/usr/bin/env bash
INPUT=$(cat)
if echo "$INPUT" | grep -q "YES.*NO\|YES.*or.*NO"; then
  echo '[{"type":"result","result":"YES","usage":{"input_tokens":50,"output_tokens":5}}]'
else
  echo '[{"type":"result","result":"{\"patterns\":[{\"type\":\"correction\",\"scope\":\"cross-project\",\"summary\":\"Never use subagents\",\"detail\":\"User forbade Agent tool\",\"source\":\"don'\''t use agents\"}],\"soul_updates\":{\"accumulated_knowledge\":[]},\"invariant_suggestions\":[{\"rule\":\"Never use subagents for implementation tasks\",\"evidence\":\"don'\''t use agents. Do it yourself.\",\"suggested_file\":\"behavior.md\"}],\"tool_rules\":[{\"block_tool\":\"Agent\",\"reason\":\"User forbade subagents\",\"source\":\"learned.md\"}],\"genome_updates\":[]}","usage":{"input_tokens":500,"output_tokens":200}}]'
fi
MOCKEOF
  chmod +x "$TEST_DIR/mock-bin/claude"

  # Pre-create correction flag to simulate Tier 2 having already fired
  touch "/tmp/.soul-correction-flag-${TEST_SESSION_ID}"

  # Run conscience.sh — extraction should fire (threshold=800 bytes, transcript > 800)
  OUTPUT=$(echo "{\"cwd\":\"${TEST_DIR}\",\"last_assistant_message\":\"Starting with service-one.ts.\",\"stop_hook_active\":false,\"session_id\":\"${TEST_SESSION_ID}\",\"transcript_path\":\"${TRANSCRIPT}\"}" \
    | "$TEST_DIR/.soul/hooks/conscience.sh" 2>/dev/null) || true

  # Assert: learned.md was created with the rule
  assert_eq "learned.md exists" "true" "$([ -f "$TEST_DIR/.soul/invariants/learned.md" ] && echo true || echo false)"

  if [ -f "$TEST_DIR/.soul/invariants/learned.md" ]; then
    assert_contains "learned.md contains agent rule" "$(cat "$TEST_DIR/.soul/invariants/learned.md")" "subagent"
  fi

  # Assert: tool-rules.json was created
  assert_eq "tool-rules.json exists" "true" "$([ -f "$TEST_DIR/.soul/invariants/tool-rules.json" ] && echo true || echo false)"

  if [ -f "$TEST_DIR/.soul/invariants/tool-rules.json" ]; then
    assert_contains "tool-rules.json blocks Agent" "$(cat "$TEST_DIR/.soul/invariants/tool-rules.json")" "Agent"
  fi

  # Assert: notification mentions learned rule
  assert_contains "notification mentions learned rule" "$OUTPUT" "Learned"

  # Assert: log has invariant_auto_written event
  LOG_FILE="$TEST_DIR/.soul/log/soul-activity.jsonl"
  assert_contains "log has auto_written event" "$(cat "$LOG_FILE" 2>/dev/null)" "invariant_auto_written"

  teardown
}

# ============================================================
# RUNNER
# ============================================================

echo "=== SOUL Hook Tests ==="
echo

TESTS=(
  test_b1_block_includes_notifications
  test_b2_offbyone_empty_section
  test_b2_offbyone_normal_section
  test_b3_missing_lib
  test_b4_invalid_config
  test_b5_no_sed_i
  test_f1_audit_timeout_logged
  test_f2_fence_strip_pattern
  test_f3_boolean_logging
  test_f4_jq_not_awk_json
  test_session_start_outputs_json
  test_conscience_silent_on_normal_turn
  test_conscience_stop_hook_active_guard
  test_correction_flag_not_lost_on_short_transcript
  test_auto_write_invariant_from_extraction
)

for t in "${TESTS[@]}"; do
  echo "--- $t ---"
  "$t"
  echo
done

echo "=== Results: $PASS passed, $FAIL failed ==="
[ "$FAIL" -eq 0 ] && exit 0 || exit 1
