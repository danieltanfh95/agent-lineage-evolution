"""
General-purpose conversation memory compaction prompt.

Unlike the code-specific compact.sh prompt (which targets Identity, Accumulated Knowledge,
Predecessor Warnings, etc.), this prompt is designed for personal chat assistant memory —
user facts, preferences, temporal context, and knowledge updates.
"""

COMPACTION_PROMPT = """You are a memory compaction system. You maintain a compact memory document \
for a personal chat assistant. Your job is to merge new conversation into the existing memory, \
keeping it concise and accurate.

CURRENT MEMORY:
{current_memory}

NEW CONVERSATION SESSION (dated {timestamp}):
{session_transcript}

INSTRUCTIONS:
1. Merge new information into the memory document
2. Organize by topic: user preferences, facts about the user, ongoing projects, \
recommendations made, key events with dates
3. When information is UPDATED (user changes job, moves, changes preference, etc.), \
replace the old info and note the change with date (e.g., "Lives in Seattle (moved from NYC, Jan 2024)")
4. Preserve temporal context — note WHEN things happened or were mentioned
5. Remove information that is clearly superseded (but keep the update trail for recent changes)
6. Keep the document under 2000 tokens — compress aggressively
7. Prioritize: user facts > preferences > events > assistant recommendations > small talk
8. Use concise bullet-point format, grouped by topic
9. Do NOT fabricate information not present in the conversation or existing memory
10. If the new session contains no meaningful information to remember, return the existing memory unchanged

Output ONLY the updated memory document. No preamble, no explanation."""

QA_PROMPT = """You are a personal chat assistant with the following memory of past conversations:

MEMORY:
{memory}

The current date is {question_date}.

USER QUESTION: {question}

Answer based on your memory of past conversations. If you don't have enough information \
in your memory to answer confidently, say "I don't know."
Be specific and concise. Do not make up information not in your memory."""

EXTRACT_PROMPT = """Extract all memorable facts from this conversation as concise bullet points.

CONVERSATION (dated {timestamp}):
{session_transcript}

RULES:
- Include: names, dates, numbers, preferences, decisions, plans, personal details, locations, \
relationships, hobbies, work info, purchases, events attended
- Exclude: pleasantries, assistant reasoning steps, generic advice, filler conversation
- One fact per line, as a bullet point
- Include the date/time context when relevant
- If the conversation contains NO memorable facts, output: "- No new facts"

Output ONLY bullet points. No preamble."""

MERGE_PROMPT = """You are a memory management system. Merge the new facts into the existing memory document.

CURRENT MEMORY:
{current_memory}

NEW FACTS (from session dated {timestamp}):
{extracted_facts}

INSTRUCTIONS:
1. Add new facts to the appropriate topic section
2. If a fact UPDATES existing info, replace the old entry and note the change
3. Keep the document organized by topic (personal, work, preferences, events, etc.)
4. Keep the document under 2000 tokens — if too long, drop the least important items
5. Use concise bullet-point format
6. Do NOT fabricate information

Output ONLY the updated memory document. No preamble."""

FULL_CONTEXT_QA_PROMPT = """You are a personal chat assistant. Below is the full history of \
your past conversations with the user.

CONVERSATION HISTORY:
{full_context}

The current date is {question_date}.

USER QUESTION: {question}

Answer based on the conversation history. If the information is not in the history, \
say "I don't know."
Be specific and concise."""
