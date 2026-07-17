package com.example.rag.memory.summary;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Config for the conversation-history summarizer (config-repo:
 * agents.summarizer.*). Blank model = fall back to rag.ollama.chat-model at
 * call time, same convention as every other agent model.
 */
@Data
@Component
@ConfigurationProperties(prefix = "agents.summarizer")
public class SummarizerProperties {

    private String model = "";
    /** Total recentTurns text length (chars) above which history gets summarized. */
    private int triggerChars = 4000;
}
