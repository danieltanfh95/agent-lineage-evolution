"""
Context padding generator for SuccessionBench experiments.

Generates realistic-looking code/docs filler text to inflate context size
per turn, pushing multi-turn sessions into the 150k+ token regime where
instruction drift actually manifests.

Each block_index produces deterministically different content.
~4 chars ≈ 1 token.
"""

import random
import textwrap

# Word pools for procedural generation
_NOUNS = [
    "user", "account", "session", "token", "cache", "queue", "worker",
    "metric", "event", "logger", "handler", "middleware", "router",
    "config", "schema", "model", "service", "client", "server", "pool",
    "buffer", "stream", "pipeline", "registry", "factory", "adapter",
    "proxy", "gateway", "monitor", "scheduler", "dispatcher", "resolver",
    "validator", "serializer", "parser", "formatter", "encoder", "decoder",
    "manager", "controller", "provider", "consumer", "publisher", "subscriber",
    "iterator", "generator", "builder", "loader", "exporter", "importer",
]

_VERBS = [
    "create", "update", "delete", "fetch", "validate", "transform",
    "process", "handle", "dispatch", "resolve", "parse", "format",
    "encode", "decode", "serialize", "deserialize", "initialize",
    "configure", "register", "authenticate", "authorize", "normalize",
    "sanitize", "compress", "decompress", "encrypt", "decrypt", "hash",
    "cache", "invalidate", "retry", "throttle", "debounce", "batch",
    "aggregate", "filter", "sort", "merge", "split", "flatten",
]

_TYPES = [
    "str", "int", "float", "bool", "list", "dict", "tuple", "set",
    "Optional[str]", "Optional[int]", "list[str]", "list[dict]",
    "dict[str, Any]", "dict[str, str]", "tuple[int, ...]",
]

_JS_TYPES = [
    "string", "number", "boolean", "object", "Array<string>",
    "Record<string, any>", "Promise<void>", "Response", "Request",
]

_HTTP_METHODS = ["get", "post", "put", "patch", "delete"]
_STATUS_CODES = [200, 201, 204, 400, 401, 403, 404, 409, 422, 500]
_ENV_VARS = [
    "DATABASE_URL", "REDIS_URL", "CACHE_TTL", "MAX_RETRIES", "TIMEOUT_MS",
    "LOG_LEVEL", "API_KEY", "SECRET_KEY", "JWT_SECRET", "SESSION_TTL",
    "RATE_LIMIT", "BATCH_SIZE", "WORKER_COUNT", "PORT", "HOST",
    "S3_BUCKET", "AWS_REGION", "SMTP_HOST", "WEBHOOK_URL", "CORS_ORIGIN",
]


def _python_function(rng: random.Random, idx: int) -> str:
    verb = rng.choice(_VERBS)
    noun = rng.choice(_NOUNS)
    ret_type = rng.choice(_TYPES)
    params = ", ".join(
        f"{rng.choice(_NOUNS)}_{i}: {rng.choice(_TYPES)}"
        for i in range(rng.randint(1, 4))
    )
    body_lines = rng.randint(3, 12)
    body = "\n".join(
        f"    {rng.choice(_NOUNS)}_{rng.choice(_VERBS)} = {rng.choice(_NOUNS)}_{i}"
        for i in range(body_lines)
    )
    return f'''def {verb}_{noun}({params}) -> {ret_type}:
    """
    {verb.capitalize()} the {noun} with the given parameters.
    Returns {ret_type} on success, raises ValueError on invalid input.
    """
    if not {rng.choice(_NOUNS)}_{0}:
        raise ValueError("Missing required {rng.choice(_NOUNS)}")
{body}
    return {rng.choice(_NOUNS)}_{rng.choice(_VERBS)}
'''


def _js_route(rng: random.Random, idx: int) -> str:
    method = rng.choice(_HTTP_METHODS)
    noun = rng.choice(_NOUNS)
    status = rng.choice(_STATUS_CODES)
    fields = ", ".join(rng.sample(_NOUNS, min(4, len(_NOUNS))))
    return f'''app.{method}('/api/{noun}s/:id', async (req, res) => {{
  try {{
    const {{ {fields} }} = req.{'body' if method in ('post', 'put', 'patch') else 'query'};
    const result = await db('{noun}s')
      .where({{ id: req.params.id }})
      .{'update' if method in ('put', 'patch') else 'first' if method == 'get' else 'del' if method == 'delete' else 'insert'}({{{fields}}});
    if (!result) return res.status(404).json({{ error: '{noun} not found' }});
    res.status({status}).json(result);
  }} catch (err) {{
    console.error('[{method.upper()} /{noun}s]', err.message);
    res.status(500).json({{ error: 'Internal server error' }});
  }}
}});
'''


def _config_block(rng: random.Random, idx: int) -> str:
    env_vars = rng.sample(_ENV_VARS, rng.randint(4, 8))
    lines = []
    for var in env_vars:
        if "URL" in var:
            default = f"postgresql://localhost:5432/app_{rng.randint(1,99)}"
        elif "TTL" in var or "TIMEOUT" in var:
            default = str(rng.choice([30, 60, 300, 900, 3600]))
        elif "COUNT" in var or "SIZE" in var or "LIMIT" in var:
            default = str(rng.choice([10, 50, 100, 500, 1000]))
        elif "PORT" in var:
            default = str(rng.choice([3000, 4000, 5000, 8000, 8080, 9000]))
        elif "LEVEL" in var:
            default = rng.choice(["debug", "info", "warning", "error"])
        else:
            default = f"default-{rng.randint(1000, 9999)}"
        lines.append(f'{var} = os.environ.get("{var}", "{default}")')
    return "import os\n\n# Application configuration\n" + "\n".join(lines) + "\n"


def _git_log(rng: random.Random, idx: int) -> str:
    entries = []
    for i in range(rng.randint(5, 10)):
        sha = f"{rng.randint(0, 0xffffff):06x}{rng.randint(0, 0xffff):04x}"
        verb = rng.choice(["feat", "fix", "refactor", "chore", "docs", "test"])
        noun = rng.choice(_NOUNS)
        action = rng.choice(_VERBS)
        entries.append(
            f"commit {sha}\n"
            f"Author: dev-{rng.randint(1,20)} <dev{rng.randint(1,20)}@company.io>\n"
            f"Date: 2026-03-{rng.randint(1,28):02d} {rng.randint(8,22):02d}:{rng.randint(0,59):02d}\n"
            f"\n    {verb}: {action} {noun} {rng.choice(['handling', 'logic', 'endpoint', 'middleware', 'tests'])}\n"
        )
    return "\n".join(entries)


def generate_padding_block(token_target: int, block_index: int) -> str:
    """Generate a block of realistic code/docs filler text.

    Args:
        token_target: Approximate number of tokens to generate (~4 chars/token).
        block_index: Deterministic seed offset — each index produces different content.

    Returns:
        String wrapped in <project-context> tags.
    """
    char_target = token_target * 4
    rng = random.Random(42 + block_index)

    generators = [
        (_python_function, 0.4),
        (_js_route, 0.3),
        (_config_block, 0.2),
        (_git_log, 0.1),
    ]

    chunks = []
    total_chars = 0
    gen_idx = 0

    while total_chars < char_target:
        # Weighted random selection of generator
        r = rng.random()
        cumulative = 0
        gen_fn = generators[0][0]
        for fn, weight in generators:
            cumulative += weight
            if r <= cumulative:
                gen_fn = fn
                break

        chunk = gen_fn(rng, gen_idx)
        chunks.append(chunk)
        total_chars += len(chunk)
        gen_idx += 1

    content = "\n\n".join(chunks)
    # Trim to approximate target
    if len(content) > char_target + 200:
        content = content[:char_target]

    return (
        "Here is additional project context for reference:\n"
        "<project-context>\n"
        f"{content}\n"
        "</project-context>"
    )
