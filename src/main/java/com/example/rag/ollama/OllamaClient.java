package com.example.rag.ollama;

import com.example.rag.config.RagProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class OllamaClient {

    private final RagProperties props;
    private final ObservationRegistry observationRegistry;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public List<Double> embed(String text) {
        String model = props.getOllama().getEmbeddingModel();
        return Observation.createNotStarted("ollama.embed", observationRegistry)
                .lowCardinalityKeyValue("model", model)
                .observe(() -> doEmbed(text, model));
    }

    private List<Double> doEmbed(String text, String model) {
        String url = props.getOllama().getBaseUrl() + "/api/embeddings";
        log.debug("[Ollama] embed() → POST {} | model={} | textLen={}", url, model, text.length());
        try {
            ObjectNode body = objectMapper.createObjectNode()
                    .put("model", model)
                    .put("prompt", text);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(props.getOllama().getTimeoutSeconds()))
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            log.debug("[Ollama] embed() ← status={} | bodyLen={}", response.statusCode(), response.body().length());

            if (response.statusCode() >= 300) {
                log.error("[Ollama] embed() failed — status={} body={}", response.statusCode(), response.body());
                throw new RuntimeException("Ollama /api/embeddings error: " + response.body());
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode emb = root.path("embedding");
            List<Double> result = new ArrayList<>(emb.size());
            for (JsonNode n : emb) {
                result.add(n.asDouble());
            }
            if (result.isEmpty()) {
                log.error("[Ollama] embed() returned empty vector — response: {}", response.body());
                throw new RuntimeException("Ollama returned an empty embedding vector for model=" + model);
            }
            log.info("[Ollama] embed() ← vector dims={}", result.size());
            return result;

        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            log.error("[Ollama] embed() exception: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to get embeddings from Ollama", e);
        }
    }

    public String chat(String userPrompt) {
        return chat(null, userPrompt);
    }

    public String chat(String systemPrompt, String userPrompt) {
        String model = props.getOllama().getChatModel();
        return Observation.createNotStarted("ollama.chat", observationRegistry)
                .lowCardinalityKeyValue("model", model)
                .observe(() -> doChat(systemPrompt, userPrompt, model));
    }

    public String rerank(String systemPrompt, String userPrompt) {
        String rerankModel = props.getOllama().getRerankModel();
        String model = (rerankModel != null && !rerankModel.isBlank())
                ? rerankModel
                : props.getOllama().getChatModel();
        return Observation.createNotStarted("ollama.rerank", observationRegistry)
                .lowCardinalityKeyValue("model", model)
                .observe(() -> doChat(systemPrompt, userPrompt, model));
    }

    private String doChat(String systemPrompt, String userPrompt, String model) {
        String url = props.getOllama().getBaseUrl() + "/api/chat";
        log.debug("[Ollama] chat() → POST {} | model={} | promptLen={}", url, model, userPrompt.length());
        try {
            ArrayNode messages = objectMapper.createArrayNode();
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                messages.add(objectMapper.createObjectNode()
                        .put("role", "system")
                        .put("content", systemPrompt));
            }
            messages.add(objectMapper.createObjectNode()
                    .put("role", "user")
                    .put("content", userPrompt));

            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", model);
            body.set("messages", messages);
            body.put("stream", false);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(props.getOllama().getTimeoutSeconds()))
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            log.debug("[Ollama] chat() ← status={} | bodyLen={}", response.statusCode(), response.body().length());

            if (response.statusCode() >= 300) {
                log.error("[Ollama] chat() failed — status={} body={}", response.statusCode(), response.body());
                throw new RuntimeException("Ollama /api/chat error: " + response.body());
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode content = root.path("message").path("content");
            if (!content.isMissingNode()) {
                String reply = content.asText();
                log.debug("[Ollama] chat() ← replyLen={}", reply.length());
                return reply;
            }

            log.warn("[Ollama] chat() unexpected response shape: {}", response.body());
            return response.body();

        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            log.error("[Ollama] chat() exception: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to call Ollama chat", e);
        }
    }
}
