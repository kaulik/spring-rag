package com.example.rag.agent.memory;

/** One conversational turn, stored as JSON in the Redis conversation list. */
public record Turn(String role, String text, long ts) {

    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";
}
