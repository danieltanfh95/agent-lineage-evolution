# Agent Lineage Evolution

Agent Lineage Evolution (ALE) is a novel framework that addresses the dual challenge of LLM agent degradation and manual prompt engineering bottlenecks. As LLMs inevitably degrade over extended usage, requiring frequent context restarts, current approaches force humans to manually re-engineer prompts with learned optimizations for each restart. ALE eliminates this scaling limitation by enabling agents to automatically generate successor prompts with embedded behavioral knowledge, transforming inevitable degradation cycles into opportunities for automated prompt evolution.

Refer to [agent lineage evolution.md](./agent%20lineage%20evolution.md) for the whitepaper.

latest meta prompt can be found [here](./meta-prompts/ale-v2.1.md)

## Usage Instructions

This is meant to be used with claude sonnet 4 and above, in chat.

1. Drop meta-prompt into chat.
2. Provide a description of the task you want to do along with context.
3. The agent will store the currrent state and latest succession document in an artifact.
4. Note to the agent if there are minor or major mistakes.
5. Tell the agent to "trigger succession" in case of major mistake.
6. The agent will update the succession package.
7. Pass the succession package and meta-prompt into new chat.

## Expected improvements

1. Better UI support for continuity. Currently we need to manually select inheritance documents. It would be great if we can start a chat with a lineage, instead of a context.