package com.example.rag.agent.graph;

import java.util.ArrayList;
import java.util.List;

/** Router output — the LLM's classification drives the graph's conditional edge. */
public enum Intent {
    KNOWLEDGE_BASE,
    STOCKS,
    GENERAL;

    /**
     * Lenient parse of a single router line: exact-word match anywhere in the
     * line, falling back to GENERAL. A sloppy classifier must never fail the
     * request.
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

    /**
     * Parses the multi-intent router output: one intent per line, most relevant
     * first. Keeps order, drops duplicates, and skips lines that name no known
     * intent (rather than mapping them to a spurious GENERAL). Empty/blank/only-
     * unparseable output falls back to a single GENERAL — the graph always has
     * at least one intent to route on. Routing uses the first element; the full
     * list is kept in state for observability.
     */
    public static List<Intent> parseAll(String raw) {
        List<Intent> intents = new ArrayList<>();
        if (raw != null) {
            for (String line : raw.split("\\R")) {
                if (line.isBlank()) {
                    continue;
                }
                String normalized = line.trim().toUpperCase();
                for (Intent intent : values()) {
                    if (normalized.contains(intent.name()) && !intents.contains(intent)) {
                        intents.add(intent);
                        break;
                    }
                }
            }
        }
        if (intents.isEmpty()) {
            intents.add(GENERAL);
        }
        return intents;
    }
}
