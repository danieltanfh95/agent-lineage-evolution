---
name: replsh
description: >
  A thinking medium for LLM agents in plan mode. Use the REPL to verify
  assumptions, inspect runtime state, and test hypotheses BEFORE writing
  code — not just to execute after. Grounds investigation in a real
  runtime instead of mental tracing, cutting hallucinated APIs and
  function signatures at the source. Supports Clojure, Python, and
  Node.js via .replsh/config.edn.
license: EPL-2.0
compatibility: Requires Babashka (bb) and replsh installed.
metadata:
  author: Daniel Tan
  repository: https://github.com/danieltanfh95/replsh
  upstream: https://raw.githubusercontent.com/danieltanfh95/replsh/main/skills/replsh/SKILL.md
---

# replsh (plan-mode focus)

Bash runs commands. A REPL is where you *think*. Plan mode is where
hallucination compounds most — every unverified assumption downstream
amplifies it. The fix is to ground investigation in a real runtime:
when you catch yourself guessing what code does, eval it instead.

## The rule

**Reach for `replsh eval` before you reach for mental tracing.**
Unverified reasoning during plan mode is the dominant source of plan
errors. The empirical finding (Succession v4, SWE-bench pytest): a
treatment run using replsh cut total tokens by ~66% vs control while
resolving the same bug, because the model stopped re-reading files to
re-verify things it had already half-understood.

## When to use the REPL during planning

- **Function signatures and return shapes.** Do not guess. Eval
  `(help function)` / `inspect(obj)` / `type(x)`.
- **Unfamiliar APIs.** Before proposing `foo.bar(baz)`, eval
  `dir(foo)` / `(keys foo)` / `Object.keys(foo)` to confirm it exists
  and takes the args you think.
- **Runtime state mid-investigation.** Import the module, instantiate
  the object, poke at it. Reading source top-down is slower and more
  error-prone.
- **Reproducing reported bugs.** Reproduce once in the REPL before
  editing anything — confirms the bug exists, confirms you understand
  it, and gives you a one-liner to re-run after the fix.
- **Checking your proposed patch mentally.** If you can eval the
  transformed expression against live data instead of tracing it,
  do that.

## How to launch and use

```bash
# Create a session config (once per repo)
mkdir -p .replsh
cat > .replsh/config.edn <<'EDN'
{:sessions
 {"work" {:toolchain "python.venv"  ;; or clojure / node
          :cwd       "./"}}}
EDN

# Launch the session (persistent — state survives between evals)
replsh launch --name work

# Eval anything
replsh eval --name work 'import foo; foo.bar()'

# Stop when done
replsh stop work
```

State persists across evals in the same session — imports, defined
functions, and bound variables stay live. Long-running evals can be
streamed or backgrounded; timeouts return partial output, so you
never lose work.

## Anti-patterns

- **Proposing code without first evaluating the assumptions it relies
  on.** Every time you catch yourself writing "this should work" in
  your plan, stop and eval it.
- **Mental tracing of multi-line expressions.** If there are two or
  more function calls chained, eval them individually.
- **Guessing function signatures from docstrings alone.** Docstrings
  drift. Call the function with a sentinel value and read the error.
- **Re-reading the same file three times to remember a shape.**
  Load it once in the REPL and query it.
- **Writing a test to verify a fix without first REPL-eval'ing the
  broken behavior.** If you cannot reproduce the bug in the REPL, you
  have not yet understood it.
