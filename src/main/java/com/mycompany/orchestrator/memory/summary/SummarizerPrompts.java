package com.mycompany.orchestrator.memory.summary;

/** Prompt text for the conversation-history summarizer. */
public final class SummarizerPrompts {

    public static final String SYSTEM_PROMPT =
            "You condense a conversation's history into a compact summary so it can continue naturally. " +
            "Preserve names, decisions, numbers, and any facts a later reply might need — drop small talk " +
            "and filler. Respond with ONLY the summary text, no preamble.";

    private SummarizerPrompts() {
    }
}
