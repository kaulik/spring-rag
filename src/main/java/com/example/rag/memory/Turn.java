package com.example.rag.memory;

import java.io.Serializable;

/**
 * One conversational turn, stored as JSON in the Redis conversation list.
 * Must implement Serializable: OrchestratorState carries List<Turn> as
 * recentTurns, and LangGraph4j's CompiledGraph.cloneState() clones state via
 * plain Java serialization (ObjectStreamStateSerializer, the framework
 * default) on every node transition — independent of, and in addition to,
 * RedisCheckpointSaver's own Jackson-based serialization for the Redis path.
 * Never caught by tests because they always pass an empty recentTurns list,
 * which serializes fine regardless of its element type (nothing inside it to
 * fail on) — only a real, non-empty list exercises this.
 */
public record Turn(String role, String text, long ts) implements Serializable {

    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";
}
