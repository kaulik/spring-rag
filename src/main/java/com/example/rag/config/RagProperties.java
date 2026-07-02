package com.example.rag.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
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
@ConfigurationProperties(prefix = "rag")
public class RagProperties {

    @Valid private Ollama ollama = new Ollama();
    @Valid private Weaviate weaviate = new Weaviate();
    @Valid private Retrieval retrieval = new Retrieval();
    @Valid private Chunking chunking = new Chunking();

    @Data
    public static class Ollama {
        @NotBlank
        private String baseUrl;
        @NotBlank
        private String embeddingModel;
        @NotBlank
        private String chatModel;
        /** Separate lighter model for reranking; falls back to chatModel if blank. */
        private String rerankModel;
        @NotNull @Positive
        private Integer timeoutSeconds;
    }

    @Data
    public static class Weaviate {
        @NotBlank
        private String baseUrl;
        @NotBlank
        private String collection;
        @NotBlank
        private String textField;
        private String apiKey;
    }

    @Data
    public static class Retrieval {
        @NotNull @Positive
        private Integer topK;
        @NotNull
        private Double hybridAlpha;
        @NotNull @Positive
        private Integer rerankTopK;
        /** Set false to skip LLM reranking and return hybrid search order directly. */
        @NotNull
        private Boolean rerankEnabled;
        /** Max chars of each doc sent to the reranker prompt. */
        @NotNull @Positive
        private Integer rerankPreviewChars;
    }

    @Data
    public static class Chunking {
        @NotNull @Positive
        private Integer sentencesPerChunk;
        @NotNull @PositiveOrZero
        private Integer tokenOverlap;
    }
}
