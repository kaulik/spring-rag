package com.example.rag.config;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Data
@Component
@Validated
@ConfigurationProperties(prefix = "rag")
public class RagProperties {

    private Ollama ollama = new Ollama();
    private Weaviate weaviate = new Weaviate();
    private Retrieval retrieval = new Retrieval();
    private Chunking chunking = new Chunking();

    @Data
    public static class Ollama {
        @NotBlank
        private String baseUrl = "http://host.docker.internal:11434";
        @NotBlank
        private String embeddingModel = "nomic-embed-text";
        @NotBlank
        private String chatModel = "llama3.2:3b";
        /** Separate lighter model for reranking; falls back to chatModel if blank. */
        private String rerankModel = "";
        private int timeoutSeconds = 60;
    }

    @Data
    public static class Weaviate {
        @NotBlank
        private String baseUrl = "http://host.docker.internal:8383";
        @NotBlank
        private String collection = "PyAgentRAGDocuments";
        @NotBlank
        private String textField = "text";
        private String apiKey;
    }

    @Data
    public static class Retrieval {
        private int topK = 5;
        private double hybridAlpha = 0.5;
        private int rerankTopK = 3;
        /** Set false to skip LLM reranking and return hybrid search order directly. */
        private boolean rerankEnabled = true;
        /** Max chars of each doc sent to the reranker prompt. */
        private int rerankPreviewChars = 150;
    }

    @Data
    public static class Chunking {
        private int sentencesPerChunk = 3;
        private int tokenOverlap = 20;
    }
}
