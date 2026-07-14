package com.example.rag.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Multi-agent knobs (config-repo: agents.*). Unlike RagProperties these
 * carry Java defaults: every agent model falls back to the pipeline chat
 * model at call time when blank (one loaded model on a memory-tight
 * Ollama host — distinct models per agent would thrash load/unload).
 */
@Data
@Component
@ConfigurationProperties(prefix = "agents")
public class AgentProperties {

    private Agent router = new Agent();
    private Agent knowledgeBase = new Agent();
    private Stock stock = new Stock();
    private Agent general = new Agent();
    private Memory memory = new Memory();

    @Data
    public static class Agent {
        /** Blank = use rag.ollama.chat-model. */
        private String model = "";
    }

    @Data
    public static class Stock {
        private String model = "";
        private String apiBaseUrl = "https://stock.indianapi.in";
    }

    @Data
    public static class Memory {
        private int window = 10;
    }
}
