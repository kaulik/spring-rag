package com.example.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "guardrails")
public class GuardrailProperties {

    private String apiKey = "changeme";
    private String allowedOrigins = "http://localhost:8282,http://localhost:3000";
    private RateLimit rateLimit = new RateLimit();
    private Input input = new Input();

    @Data
    public static class RateLimit {
        private int requestsPerMinute = 30;
    }

    @Data
    public static class Input {
        private int maxQueryLength = 1000;
        private int maxContextLength = 50000;
    }
}
