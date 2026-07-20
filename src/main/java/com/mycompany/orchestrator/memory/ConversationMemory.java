package com.mycompany.orchestrator.memory;

import java.util.List;

/**
 * Conversation-memory port — the swappable seam for dialogue history.
 * Consumers depend on this interface, not on any store, so switching Redis
 * for another backend is a drop-in adapter + a bean swap. Implementations
 * are fail-open: a store outage degrades to memory-less answers, never a
 * failed request.
 *
 * The current adapter is {@link com.mycompany.orchestrator.memory.redis.RedisConversationMemory}.
 */
public interface ConversationMemory {

    /** Append one turn to a conversation, trimming to the configured window. */
    void append(String conversationId, Turn turn);

    /** The most recent turns for a conversation, oldest first (empty if none / store down). */
    List<Turn> recentTurns(String conversationId);

    /** Replace the stored history for a conversation (e.g. after summarization compacts it). */
    void replace(String conversationId, List<Turn> turns);
}
