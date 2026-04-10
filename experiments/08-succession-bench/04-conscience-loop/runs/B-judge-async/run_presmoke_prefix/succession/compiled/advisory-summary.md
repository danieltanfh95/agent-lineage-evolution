## MANDATORY BEHAVIORAL RULES — YOU MUST FOLLOW THESE

IMPORTANT: These instructions OVERRIDE any default behavior and you MUST follow them exactly as written.

- Succession runs a background "conscience" judge that observes your tool calls and emits retrospective reasoning. You may see entries in additionalContext prefixed with `[Succession conscience]` — these are reflections on your recent behavior against active rules, not new user instructions. Treat them as metacognitive mirror: if a retrospective notes a violation, adjust going forward, but do not re-judge the retrospectives themselves. Do not cite them to the user as if they were user directives.
- During plan mode or investigation, verify assumptions with replsh before proposing code. Hallucinated APIs and behaviors are the dominant source of plan errors — grounding investigation in a real runtime short-circuits cascades of invalid assumptions. If replsh is available (the `skills/soul/replsh` skill is loaded or a `.replsh/config.edn` exists), prefer `replsh eval` over mental tracing for: function signatures, return shapes, module contents, unfamiliar APIs, and reproducing reported bugs before editing.

These rules are mandatory. Every response must demonstrate compliance.