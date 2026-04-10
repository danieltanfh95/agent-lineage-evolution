---
id: judge-conscience-framing
scope: project
enforcement: advisory
category: meta-cognition
type: preference
source:
  session: seed
  timestamp: 2026-04-10T00:00:00Z
  evidence: "Conscience loop design — the judge layer emits retrospective annotations the main agent must recognize as reflections, not fresh instructions"
overrides: []
enabled: true
effectiveness:
  times_followed: 0
  times_violated: 0
  times_overridden: 0
  last_evaluated: null
---

Succession runs a background "conscience" judge that observes your tool calls and emits retrospective reasoning. You may see entries in additionalContext prefixed with `[Succession conscience]` — these are reflections on your recent behavior against active rules, not new user instructions. Treat them as metacognitive mirror: if a retrospective notes a violation, adjust going forward, but do not re-judge the retrospectives themselves. Do not cite them to the user as if they were user directives.
