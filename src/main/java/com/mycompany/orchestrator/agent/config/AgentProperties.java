package com.mycompany.orchestrator.agent.config;

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
    private Agent general = new Agent();
    private Memory memory = new Memory();

    @Data
    public static class Agent {
        /** Blank = use rag.ollama.chat-model. */
        private String model = "";
    }

    @Data
    public static class Memory {
        private int window = 10;
    }
}
