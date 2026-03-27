# LinkedIn Post Draft — SOUL Framework

## Above the fold (~1,300 chars)

Claude Code forgets everything between sessions.

After 10 sessions of building an Express API — adding auth, refactoring the DB, swapping cache libraries — I asked it 20 questions about the project. Without memory: 6/20. With SOUL: 20/20.

SOUL gives Claude Code agents persistent identity. Not just memory — identity. Who it is, what it knows, what mistakes its predecessors made, and rules it cannot break.

Five components, zero config:
- SOUL.md: Agent identity loaded every session
- Invariants: Rules the agent cannot violate
- Conscience: Automated audit loop checking every response
- Genome Cascade: Shared knowledge across repos
- Rolling Compaction: Memory that compresses, not grows

The compaction story is wild. Over 50 conversation sessions (~500k chars), Sonnet 4.6 compresses to 5.8k chars — an 86x compression ratio — while retaining enough detail to answer factual questions correctly.

Install in 30 seconds:
npx skills add danieltanfh95/agent-lineage-evolution --skill soul

Then type /soul setup in Claude Code.

## Below the fold

The most surprising finding: model choice matters more than prompt engineering for compaction. Haiku 4.5 completely fails — its "compacted" memory balloons to 34k chars (it copies instead of compressing). Sonnet 4.6 with extended thinking stays stable at 5.8k chars. Same prompt, dramatically different results.

Honest caveats:
- At small scale (10 sessions), append-only memory scores equally well — SOUL's advantage shows at scale
- The LongMemEval benchmark was only run on 5 instances (preliminary)
- Instruction drift (Opus ignoring rules past 150k tokens) is observed but not yet reproducibly benchmarked

SOUL extends my Agent Lineage Evolution (ALE) framework from 2025. ALE used generational succession — agents dying and passing knowledge to successors. SOUL replaces that with continuous governance via Claude Code's hooks system.

Full whitepaper, experiments, and code: https://github.com/danieltanfh95/agent-lineage-evolution

Open source, MIT licensed. Built entirely with Claude Code.

#ClaudeCode #LLM #AgentGovernance #DeveloperTools #AI #OpenSource
