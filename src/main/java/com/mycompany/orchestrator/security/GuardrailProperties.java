package com.mycompany.orchestrator.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * All values are supplied by the Spring Cloud Config server (config-repo/spring-rag.yml).
 * No defaults are defined here on purpose: a missing property must fail startup
 * rather than silently run with a stale fallback.
 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "guardrails")
public class GuardrailProperties {

    @NotBlank
    private String apiKey;
    @NotBlank
    private String allowedOrigins;
    @Valid private RateLimit rateLimit = new RateLimit();
    @Valid private Input input = new Input();

    @Data
    public static class RateLimit {
        @NotNull @Positive
        private Integer requestsPerMinute;
    }

    @Data
    public static class Input {
        @NotNull @Positive
        private Integer maxQueryLength;
        @NotNull @Positive
        private Integer maxContextLength;
    }
}
