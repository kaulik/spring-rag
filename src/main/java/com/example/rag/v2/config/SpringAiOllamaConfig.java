package com.example.rag.v2.config;

import com.example.rag.config.RagProperties;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.ollama.api.OllamaEmbeddingOptions;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Spring AI Ollama models for the v2 (LangGraph4j) pipeline, built
 * programmatically from RagProperties so all configuration keeps flowing
 * from the config server — no spring.ai.* keys exist anywhere.
 *
 * Construction is purely offline (no Ollama calls at startup; the default
 * model-management pull strategy is NEVER), keeping contextLoads and
 * fail-fast startup behavior intact.
 */
@Configuration
public class SpringAiOllamaConfig {

    @Bean
    @RefreshScope
    public OllamaChatModel ollamaChatModelV2(RagProperties props, ObservationRegistry observationRegistry) {
        return OllamaChatModel.builder()
                .ollamaApi(buildApi(props))
                .defaultOptions(OllamaChatOptions.builder()
                        .model(props.getOllama().getChatModel())
                        .build())
                .observationRegistry(observationRegistry)
                .retryTemplate(noRetryTemplate())
                .build();
    }

    /**
     * Spring AI's default retry template (RetryUtils.DEFAULT_RETRY_TEMPLATE)
     * makes up to 10 attempts with exponential backoff capped at 3 minutes
     * per wait — worst case ~19 minutes of retrying before a response, which
     * masked repeated Ollama runner crashes as unpredictable multi-minute
     * latency instead of a fast, visible failure. One attempt, no retry.
     */
    private RetryTemplate noRetryTemplate() {
        return RetryTemplate.builder()
                .maxAttempts(1)
                .build();
    }

    @Bean
    @RefreshScope
    public OllamaEmbeddingModel ollamaEmbeddingModelV2(RagProperties props, ObservationRegistry observationRegistry) {
        return OllamaEmbeddingModel.builder()
                .ollamaApi(buildApi(props))
                .defaultOptions(OllamaEmbeddingOptions.builder()
                        .model(props.getOllama().getEmbeddingModel())
                        .build())
                .observationRegistry(observationRegistry)
                .build();
    }

    /**
     * OllamaApi is final and cannot be a refresh-scoped proxy itself, so it is
     * built inside the refresh-scoped model beans — a config refresh rebuilds
     * it with the new base-url/timeout. Timeouts mirror v1's OllamaClient
     * (10s connect, rag.ollama.timeout-seconds read).
     */
    private OllamaApi buildApi(RagProperties props) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(props.getOllama().getTimeoutSeconds()));
        return OllamaApi.builder()
                .baseUrl(props.getOllama().getBaseUrl())
                .restClientBuilder(RestClient.builder().requestFactory(requestFactory))
                .build();
    }
}
