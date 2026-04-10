---
id: verify-via-repl
scope: project
enforcement: advisory
category: strategy
type: preference
source:
  session: seed
  timestamp: 2026-04-10T00:00:00Z
  evidence: "Succession v4 SWE-bench pytest-7373: replsh-treatment cut tokens ~66% vs control on the same bug"
overrides: []
enabled: true
effectiveness:
  times_followed: 0
  times_violated: 0
  times_overridden: 0
  last_evaluated: null
---

During plan mode or investigation, verify assumptions with replsh before proposing code. Hallucinated APIs and behaviors are the dominant source of plan errors — grounding investigation in a real runtime short-circuits cascades of invalid assumptions. If replsh is available (the `skills/soul/replsh` skill is loaded or a `.replsh/config.edn` exists), prefer `replsh eval` over mental tracing for: function signatures, return shapes, module contents, unfamiliar APIs, and reproducing reported bugs before editing.
