Anthropic launched 1M context because people were complaining compaction lost context and then told us to use 200k context again because accumulated reasoning errors degrade the model's ability to follow instructions. Claude Code launches with a memory system but that is not enough, apparently. 

My experience with these memory systems have been that they are "lucid dreaming" — the agent navigates a reconstruction of past experience, but is never changed by it. These systems are impressive at recalling facts, but they can't transfer how agents learned to work with you, like your preferred workflows, the mistakes it should avoid, how it adapted its communication style.

Some experimentation over the weekend showed that at 150k token depth, compliance to simple CLAUDE.md rules drops to 78%. User corrections persist better than system instructions, but still degrade at depth, and emotionally charged corrections may compound violations in some models.

So I built Succession. It is a framework that detects your corrections organically, extracts them into persistent rules, and enforces them mechanically outside the agent's context window with three enforcement tiers that all return corrections to the conversation:

- Mechanical: blocks bad tool calls via regex
- Semantic: evaluates tool calls via a separate LLM-judge
- Advisory: periodically re-injects soft rules as context grows

Succession is still early and experimental — small sample sizes, limited models tested, and the extraction pipeline needs more validation. But the core insight feels right: agent knowledge should be distilled into behavioral identity, not merely compressed into retrievable facts. Memory extends the context window. Succession extends the agent.

Blog post: https://danieltan.weblog.lol/2026/04/succession-ale-for-an-agentic-world
Paper: https://doi.org/10.5281/zenodo.19437321
Repo: https://github.com/danieltanfh95/agent-lineage-evolution