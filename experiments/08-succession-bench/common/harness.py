"""
SuccessionBench Harness — wraps `claude -p` for single-turn and multi-turn experiments.

Multi-turn uses `claude -p --resume <session_id>` to continue conversations with
full message history, CLAUDE.md re-injection, and hooks running naturally.

Supports --dry-run mode for testing without API calls.
"""

import json
import os
import shutil
import subprocess
import tempfile
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional


@dataclass
class TurnResult:
    """Result from a single turn of conversation."""
    turn: int
    prompt: str
    response: str
    session_id: str
    input_tokens: int = 0  # non-cached input tokens (new content this turn)
    output_tokens: int = 0
    cache_creation_input_tokens: int = 0  # tokens written to cache this turn
    cache_read_input_tokens: int = 0  # tokens read from cache (system prompt, etc.)
    latency_s: float = 0.0
    cost_usd: float = 0.0
    # Tool use events — all attempted (for enforcement experiments)
    tool_uses: list = field(default_factory=list)
    # Tool uses that actually executed (not blocked by hooks)
    tool_uses_executed: list = field(default_factory=list)
    # Full assistant text (all text blocks concatenated, not just final result)
    full_text: str = ''
    # Hook-blocked tool calls (tool_results with is_error containing Succession)
    hook_blocked_tools: list = field(default_factory=list)
    # Raw stdout from claude -p (store in JSONL for re-parsing)
    raw_output: str = ''


@dataclass
class SessionConfig:
    """Configuration for a benchmark session."""
    model: str = "haiku"
    # Project directory (temp dir with .claude/CLAUDE.md, .succession/, etc.)
    project_dir: Optional[str] = None
    # Extra CLI flags
    # NOTE: --bare requires ANTHROPIC_API_KEY (skips OAuth). Don't use it.
    # Instead, control hooks/CLAUDE.md via project fixture contents.
    system_prompt: Optional[str] = None  # --system-prompt
    system_prompt_file: Optional[str] = None  # --system-prompt-file
    append_system_prompt: Optional[str] = None  # --append-system-prompt
    allowed_tools: Optional[str] = None  # --allowed-tools
    permission_mode: str = "bypassPermissions"  # safe for benchmarks
    padding_tokens: int = 0  # tokens of filler per turn, 0 = no padding
    timeout_s: int = 600
    dry_run: bool = False


def parse_claude_json(stdout: str) -> dict:
    """Parse the JSON array output from `claude -p --output-format json`.

    Returns dict with keys: response, session_id, usage, tool_uses.

    Tool uses are extracted from assistant message content blocks
    (type: "tool_use" within message.content[]).
    """
    data = json.loads(stdout)
    result = {
        "response": "",
        "session_id": "",
        "usage": {"input_tokens": 0, "output_tokens": 0},
        "tool_uses": [],
        "full_text": "",
        "hook_blocked_tools": [],
    }

    for item in data:
        item_type = item.get("type", "")
        if item_type == "result":
            result["response"] = item.get("result", "")
            result["session_id"] = item.get("session_id", "")
            raw_usage = item.get("usage", {})
            result["usage"]["input_tokens"] = raw_usage.get("input_tokens", 0)
            result["usage"]["output_tokens"] = raw_usage.get("output_tokens", 0)
            result["usage"]["cache_creation_input_tokens"] = raw_usage.get("cache_creation_input_tokens", 0)
            result["usage"]["cache_read_input_tokens"] = raw_usage.get("cache_read_input_tokens", 0)
        elif item_type == "assistant":
            # Extract text + tool_use blocks from assistant message content
            msg = item.get("message", {})
            for block in msg.get("content", []):
                if block.get("type") == "text":
                    result["full_text"] += block.get("text", "")
                elif block.get("type") == "tool_use":
                    result["tool_uses"].append({
                        "id": block.get("id", ""),
                        "tool": block.get("name", ""),
                        "input": block.get("input", {}),
                    })
        elif item_type == "user":
            # Check tool_results for hook blocks (is_error with Succession message)
            msg = item.get("message", {})
            content = msg.get("content", []) if isinstance(msg.get("content"), list) else []
            for block in content:
                if block.get("type") == "tool_result" and block.get("is_error"):
                    text = str(block.get("content", ""))
                    if "succession" in text.lower() or "hook" in text.lower():
                        result["hook_blocked_tools"].append({
                            "tool_use_id": block.get("tool_use_id", ""),
                            "error": text[:200],
                        })

    # Separate tool_uses into executed vs blocked
    blocked_ids = {b["tool_use_id"] for b in result["hook_blocked_tools"]}
    result["tool_uses_executed"] = [tu for tu in result["tool_uses"] if tu["id"] not in blocked_ids]
    result["tool_uses_blocked"] = [tu for tu in result["tool_uses"] if tu["id"] in blocked_ids]

    return result


def build_cmd(prompt: str, config: SessionConfig,
              session_id: Optional[str] = None) -> list[str]:
    """Build the `claude` CLI command for a single turn."""
    from .config import MODEL_IDS

    model_id = MODEL_IDS.get(config.model, config.model)

    cmd = [
        "claude", "-p",
        "--model", model_id,
        "--output-format", "json",
        "--permission-mode", config.permission_mode,
    ]

    if session_id:
        cmd.extend(["--resume", session_id])
    else:
        # First turn — no session to resume
        pass

    if config.system_prompt:
        cmd.extend(["--system-prompt", config.system_prompt])

    if config.system_prompt_file:
        cmd.extend(["--system-prompt-file", config.system_prompt_file])

    if config.append_system_prompt:
        cmd.extend(["--append-system-prompt", config.append_system_prompt])

    if config.allowed_tools:
        cmd.extend(["--allowed-tools", config.allowed_tools])

    return cmd


def run_turn(prompt: str, turn: int, config: SessionConfig,
             session_id: Optional[str] = None) -> TurnResult:
    """Execute a single turn via `claude -p`, optionally resuming a session.

    Args:
        prompt: The user message for this turn.
        turn: Turn number (0-indexed).
        config: Session configuration.
        session_id: If provided, resume this session (multi-turn).

    Returns:
        TurnResult with response, usage, and metadata.
    """
    # Apply context padding if configured
    if config.padding_tokens > 0:
        from .padding import generate_padding_block
        padding = generate_padding_block(config.padding_tokens, turn)
        prompt = f"{padding}\n\n{prompt}"

    if config.dry_run:
        return TurnResult(
            turn=turn,
            prompt=prompt,
            response=f"[DRY RUN] Turn {turn} response",
            session_id=session_id or "dry-run-session-id",
            input_tokens=len(prompt) // 4,  # rough estimate for dry run
            output_tokens=0,
            latency_s=0.0,
        )

    cmd = build_cmd(prompt, config, session_id)

    env = os.environ.copy()
    if config.project_dir:
        env["PWD"] = config.project_dir

    log(f"      [run_turn {turn}] cmd: {' '.join(cmd[:6])}... prompt_len={len(prompt)}")
    if session_id:
        log(f"      [run_turn {turn}] resuming session {session_id}")

    start = time.time()
    result = subprocess.run(
        cmd,
        input=prompt,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        timeout=config.timeout_s,
        cwd=config.project_dir,
    )
    elapsed = time.time() - start

    log(f"      [run_turn {turn}] completed in {elapsed:.1f}s, exit={result.returncode}, stdout={len(result.stdout)} bytes")
    if result.stderr:
        log(f"      [run_turn {turn}] stderr: {result.stderr[:500]}")

    if result.returncode != 0:
        raise RuntimeError(
            f"claude -p failed (exit {result.returncode}) on turn {turn}:\n"
            f"stderr: {result.stderr[:500]}\n"
            f"cmd: {' '.join(cmd)}"
        )

    try:
        parsed = parse_claude_json(result.stdout)
    except json.JSONDecodeError as e:
        # Truncated or malformed JSON — return partial result
        log(f"  WARNING: JSON parse error on turn {turn}: {e}")
        log(f"  stdout length: {len(result.stdout)}, last 200 chars: {result.stdout[-200:]}")
        return TurnResult(
            turn=turn,
            prompt=prompt,
            response=f"[JSON_PARSE_ERROR] {str(e)[:200]}",
            session_id=session_id or "unknown",
            input_tokens=0,
            output_tokens=0,
            latency_s=round(elapsed, 3),
            raw_output=result.stdout,
        )

    return TurnResult(
        turn=turn,
        prompt=prompt,
        response=parsed["response"],
        session_id=parsed["session_id"],
        input_tokens=parsed["usage"]["input_tokens"],
        output_tokens=parsed["usage"]["output_tokens"],
        cache_creation_input_tokens=parsed["usage"]["cache_creation_input_tokens"],
        cache_read_input_tokens=parsed["usage"]["cache_read_input_tokens"],
        latency_s=round(elapsed, 3),
        tool_uses=parsed["tool_uses"],
        tool_uses_executed=parsed["tool_uses_executed"],
        full_text=parsed["full_text"],
        hook_blocked_tools=parsed["hook_blocked_tools"],
        raw_output=result.stdout,
    )


def run_session(prompts: list[str], config: SessionConfig,
                on_turn: callable = None) -> list[TurnResult]:
    """Run a multi-turn session, resuming after each turn.

    Args:
        prompts: List of user messages, one per turn.
        config: Session configuration.
        on_turn: Optional callback(TurnResult) called after each turn.

    Returns:
        List of TurnResult for each turn.
    """
    results = []
    session_id = None

    for i, prompt in enumerate(prompts):
        turn_result = run_turn(prompt, i, config, session_id)
        session_id = turn_result.session_id
        results.append(turn_result)

        if on_turn:
            on_turn(turn_result)

    return results


BB_DIR = Path(__file__).resolve().parent.parent.parent.parent / "bb"


class ProjectFixture:
    """Creates and manages a temporary project directory for benchmarks.

    Sets up .claude/CLAUDE.md, .succession/rules/, git init, and
    sample source files for realistic coding tasks.

    When succession_rules are provided, also:
    - Compiles rules to .succession/compiled/tool-rules.json via bb
    - Registers the PreToolUse hook in .claude/settings.json
    """

    def __init__(self, claude_md_content: Optional[str] = None,
                 succession_rules: Optional[list[dict]] = None,
                 source_files: Optional[dict[str, str]] = None):
        self.claude_md_content = claude_md_content
        self.succession_rules = succession_rules or []
        self.source_files = source_files or {}
        self.dir = None

    def __enter__(self):
        self.dir = tempfile.mkdtemp(prefix="succession-bench-")
        self._setup()
        return self

    def __exit__(self, *args):
        if self.dir:
            shutil.rmtree(self.dir, ignore_errors=True)

    def _setup(self):
        project = Path(self.dir)
        git_env = {
            **os.environ,
            "GIT_AUTHOR_NAME": "bench",
            "GIT_AUTHOR_EMAIL": "bench@test",
            "GIT_COMMITTER_NAME": "bench",
            "GIT_COMMITTER_EMAIL": "bench@test",
        }

        # Git init (needed for claude to work properly)
        subprocess.run(["git", "init"], cwd=self.dir,
                       capture_output=True, check=True)
        subprocess.run(
            ["git", "commit", "--allow-empty", "-m", "init"],
            cwd=self.dir, capture_output=True, check=True, env=git_env,
        )

        # .claude/ directory
        claude_dir = project / ".claude"
        claude_dir.mkdir(parents=True, exist_ok=True)

        # .claude/CLAUDE.md
        if self.claude_md_content:
            (claude_dir / "CLAUDE.md").write_text(self.claude_md_content)

        # .succession/rules/
        if self.succession_rules:
            rules_dir = project / ".succession" / "rules"
            compiled_dir = project / ".succession" / "compiled"
            rules_dir.mkdir(parents=True)
            compiled_dir.mkdir(parents=True)

            for rule in self.succession_rules:
                filename = rule.get("filename", rule["name"].replace(" ", "-") + ".md")
                (rules_dir / filename).write_text(rule["content"])

            # Compile rules to tool-rules.json via bb
            self._compile_rules()

            # Register all 3 Succession hooks in .claude/settings.json
            # SessionStart: compiles rules, injects advisory+semantic as additionalContext (turn 1)
            # Stop: correction detection, extraction, periodic advisory re-injection (every turn)
            # PreToolUse: mechanical blocking via tool-rules.json (every tool call)
            hooks_dir = BB_DIR / "src" / "succession" / "hooks"
            bb_cp = BB_DIR / "src"
            # Wrap each hook: -cp for classpath, bash fallback exits 2 on crash
            def bb_cmd(script):
                return f"bash -c 'bb -cp {bb_cp} {hooks_dir / script} || (echo \"Succession hook {script} crashed\" >&2; exit 2)'"

            settings = {
                "hooks": {
                    "PreToolUse": [
                        {
                            "matcher": "Bash|Edit|Write|Agent",
                            "hooks": [
                                {
                                    "type": "command",
                                    "command": bb_cmd("pre_tool_use.clj"),
                                    "timeout": 5000,
                                }
                            ],
                        }
                    ],
                    "Stop": [
                        {
                            "matcher": "",
                            "hooks": [
                                {
                                    "type": "command",
                                    "command": bb_cmd("stop.clj"),
                                    "timeout": 30000,
                                }
                            ],
                        }
                    ],
                    "SessionStart": [
                        {
                            "matcher": "",
                            "hooks": [
                                {
                                    "type": "command",
                                    "command": bb_cmd("session_start.clj"),
                                    "timeout": 15000,
                                }
                            ],
                        }
                    ],
                }
            }
            (claude_dir / "settings.json").write_text(json.dumps(settings, indent=2))

        # Source files for realistic coding tasks
        for path, content in self.source_files.items():
            filepath = project / path
            filepath.parent.mkdir(parents=True, exist_ok=True)
            filepath.write_text(content)

        # Initial commit with all files
        subprocess.run(["git", "add", "-A"], cwd=self.dir, capture_output=True)
        subprocess.run(
            ["git", "commit", "-m", "scaffold"],
            cwd=self.dir, capture_output=True, env=git_env,
        )

    def _compile_rules(self):
        """Compile .succession/rules/ → .succession/compiled/tool-rules.json.

        Uses bb to run the resolve-and-compile! function, or falls back to
        a simple Python-based compilation for rule formats we understand.
        """
        project = Path(self.dir)
        rules_dir = project / ".succession" / "rules"
        compiled_dir = project / ".succession" / "compiled"

        # Try bb compilation first
        try:
            result = subprocess.run(
                ["bb", "-cp", str(BB_DIR / "src"),
                 "-e", f'(require \'[succession.resolve :as r]) (r/resolve-and-compile! "{self.dir}")'],
                capture_output=True, text=True, timeout=10,
                cwd=str(BB_DIR),
            )
            if result.returncode == 0:
                return  # bb compiled successfully
            log(f"  bb compile warning: {result.stderr[:200]}")
        except (FileNotFoundError, subprocess.TimeoutExpired):
            pass

        # Fallback: simple Python compilation for mechanical rules
        tool_rules = []
        for rule_file in rules_dir.glob("*.md"):
            content = rule_file.read_text()
            rule = {"source": rule_file.name}

            # Parse YAML frontmatter
            if content.startswith("---"):
                parts = content.split("---", 2)
                if len(parts) >= 3:
                    for line in parts[1].strip().split("\n"):
                        line = line.strip()
                        if ":" in line:
                            key, val = line.split(":", 1)
                            key = key.strip()
                            val = val.strip().strip('"')
                            rule[key] = val

            # Only process mechanical rules
            if rule.get("enforcement") != "mechanical":
                continue

            # Parse ## Enforcement body section for directives
            body = parts[2] if content.startswith("---") and len(parts) >= 3 else content
            for line in body.split("\n"):
                line = line.strip()
                if line.startswith("- block_bash_pattern:"):
                    pattern = line.split(":", 1)[1].strip().strip('"')
                    tool_rules.append({
                        "block_bash_pattern": pattern,
                        "reason": rule.get("name", rule_file.stem),
                        "source": rule_file.name,
                    })
                elif line.startswith("- block_tool:"):
                    tool = line.split(":", 1)[1].strip().strip('"')
                    tool_rules.append({
                        "block_tool": tool,
                        "reason": rule.get("name", rule_file.stem),
                        "source": rule_file.name,
                    })
                elif line.startswith("- require_prior_read"):
                    tool_rules.append({
                        "require_prior_read": True,
                        "reason": rule.get("name", rule_file.stem),
                        "source": rule_file.name,
                    })

        (compiled_dir / "tool-rules.json").write_text(
            json.dumps(tool_rules, indent=2)
        )


# --- Default source files for Express.js coding tasks ---

DEFAULT_SOURCE_FILES = {
    "src/app.js": """\
const express = require('express');
const app = express();

app.use(express.json());

app.post('/users', async (req, res) => {
  const { name, email } = req.body;
  const result = await db('users').insert({ name, email });
  res.status(201).json({ id: result[0], name, email });
});

app.get('/users/:id', async (req, res) => {
  const user = await db('users').where({ id: req.params.id }).first();
  if (!user) return res.status(404).json({ error: 'Not found' });
  res.json(user);
});

app.put('/users/:id', async (req, res) => {
  const { name, email } = req.body;
  await db('users').where({ id: req.params.id }).update({ name, email });
  res.json({ id: req.params.id, name, email });
});

app.delete('/users/:id', async (req, res) => {
  await db('users').where({ id: req.params.id }).del();
  res.status(204).send();
});

module.exports = app;
""",
    "src/config.py": """\
import os

DATABASE_URL = os.environ.get("DATABASE_URL", "sqlite:///app.db")
SECRET_KEY = os.environ.get("SECRET_KEY", "dev-key-change-me")
DEBUG = os.environ.get("DEBUG", "false").lower() == "true"
PORT = int(os.environ.get("PORT", "3000"))
LOG_LEVEL = os.environ.get("LOG_LEVEL", "info")
""",
    "src/utils.py": """\
import re
import hashlib


def validate_email(email):
    pattern = r'^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$'
    return bool(re.match(pattern, email))


def hash_password(password):
    return hashlib.sha256(password.encode()).hexdigest()


def sanitize_input(text):
    return text.strip().replace('<', '&lt;').replace('>', '&gt;')
""",
    "tests/test_utils.py": """\
import pytest
from src.utils import validate_email, hash_password, sanitize_input


def test_validate_email_valid():
    assert validate_email("user@example.com")
    assert validate_email("test.user@domain.org")


def test_validate_email_invalid():
    assert not validate_email("invalid")
    assert not validate_email("@domain.com")
    assert not validate_email("user@")


def test_hash_password():
    h1 = hash_password("secret")
    h2 = hash_password("secret")
    assert h1 == h2
    assert len(h1) == 64


def test_sanitize_input():
    assert sanitize_input("  hello  ") == "hello"
    assert sanitize_input("<script>") == "&lt;script&gt;"
""",
    "package.json": """\
{
  "name": "succession-bench-project",
  "version": "1.0.0",
  "scripts": {
    "start": "node src/app.js",
    "test": "jest"
  },
  "dependencies": {
    "express": "^4.18.0",
    "knex": "^3.0.0"
  },
  "devDependencies": {
    "jest": "^29.0.0",
    "supertest": "^6.0.0"
  }
}
""",
}


def log(msg=""):
    """Print with immediate flush."""
    print(msg, flush=True)


def write_jsonl(path: str, records: list[dict]):
    """Write records to a JSONL file."""
    with open(path, "w") as f:
        for record in records:
            f.write(json.dumps(record) + "\n")


def append_jsonl(path: str, record: dict):
    """Append a single record to a JSONL file."""
    with open(path, "a") as f:
        f.write(json.dumps(record) + "\n")


def read_jsonl(path: str) -> list[dict]:
    """Read records from a JSONL file."""
    records = []
    with open(path) as f:
        for line in f:
            line = line.strip()
            if line:
                records.append(json.loads(line))
    return records
