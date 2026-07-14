package com.example.rag.agent.graph;

/** Router output — the LLM's classification drives the graph's conditional edge. */
public enum Intent {
    KNOWLEDGE_BASE,
    STOCKS,
    GENERAL;

    /**
     * Lenient parse of the router LLM's output: exact-word match anywhere in
     * the response, falling back to GENERAL. A sloppy classifier must never
     * fail the request.
     */
    public static Intent parse(String raw) {
        if (raw == null) {
            return GENERAL;
        }
        String normalized = raw.trim().toUpperCase();
        for (Intent intent : values()) {
            if (normalized.contains(intent.name())) {
                return intent;
            }
        }
        return GENERAL;
    }
}
