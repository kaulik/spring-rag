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
        private String model = "llama3.1";
    }

    @Data
    public static class Weaviate {
        @NotBlank
        private String baseUrl = "http://host.docker.internal:8383";
        @NotBlank
        private String collection = "pyAgentRAGDocuments";
        @NotBlank
        private String textField = "text";
        private String apiKey;
    }

    @Data
    public static class Retrieval {
        private int topK = 10;
        private double hybridAlpha = 0.5;
        private int rerankTopK = 5;
    }

    @Data
    public static class Chunking {
        private int sentencesPerChunk = 3;
        private int tokenOverlap = 20;
    }
}
