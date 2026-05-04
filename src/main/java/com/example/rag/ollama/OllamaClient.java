package com.example.rag.ollama;

import com.example.rag.config.RagProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class OllamaClient {

    private final RagProperties props;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public List<Double> embed(String text) {
        String url = props.getOllama().getBaseUrl() + "/api/embeddings";
        String model = props.getOllama().getModel();
        log.debug("[Ollama] embed() → POST {} | model={} | textLen={}", url, model, text.length());
        try {
            ObjectNode body = objectMapper.createObjectNode()
                    .put("model", model)
                    .put("prompt", text);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
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
            log.debug("[Ollama] embed() ← vector dims={}", result.size());
            return result;

        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            log.error("[Ollama] embed() exception: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to get embeddings from Ollama", e);
        }
    }

    public String chat(String userPrompt) {
        String url = props.getOllama().getBaseUrl() + "/api/chat";
        String model = props.getOllama().getModel();
        log.debug("[Ollama] chat() → POST {} | model={} | promptLen={}", url, model, userPrompt.length());
        try {
            ArrayNode messages = objectMapper.createArrayNode();
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
