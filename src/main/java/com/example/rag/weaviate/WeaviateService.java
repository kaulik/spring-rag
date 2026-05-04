package com.example.rag.weaviate;

import com.example.rag.config.RagProperties;
import com.example.rag.service.ChunkingService.Chunk;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import lombok.AllArgsConstructor;
import lombok.Data;
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
import java.util.stream.Collectors;

/**
 * Weaviate integration via REST + GraphQL.
 *
 * Ingest  → POST /v1/batch/objects  (stores text + metadata + Ollama vector)
 * Search  → POST /v1/graphql         (hybrid BM25 + vector query)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WeaviateService {

    private final RagProperties props;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Data
    @AllArgsConstructor
    public static class RetrievedDoc {
        private String text;
        private String source;
        private String chunkId;
    }

    // -------------------------------------------------------------------------
    // Schema bootstrap
    // -------------------------------------------------------------------------

    @PostConstruct
    public void ensureCollectionExists() {
        String collection = props.getWeaviate().getCollection();
        String baseUrl    = props.getWeaviate().getBaseUrl();
        try {
            int status = getStatus(baseUrl + "/v1/schema/" + collection);
            if (status == 200) {
                log.info("Weaviate collection '{}' already exists", collection);
                return;
            }

            String schema = String.format("""
                    {
                      "class": "%s",
                      "vectorizer": "none",
                      "properties": [
                        {"name": "text",     "dataType": ["text"]},
                        {"name": "source",   "dataType": ["text"]},
                        {"name": "chunk_id", "dataType": ["text"]}
                      ]
                    }
                    """, collection);

            post(baseUrl + "/v1/schema", schema);
            log.info("Created Weaviate collection '{}'", collection);
        } catch (Exception e) {
            log.warn("Could not ensure Weaviate collection '{}' exists: {}", collection, e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Ingest
    // -------------------------------------------------------------------------

    /**
     * Batch-insert chunks into Weaviate together with their pre-computed
     * embedding vectors (supplied by OllamaClient).
     */
    public void ingestChunks(List<Chunk> chunks, List<List<Double>> embeddings) {
        if (chunks.isEmpty()) return;
        if (embeddings.size() != chunks.size()) {
            throw new IllegalArgumentException(
                    "chunks.size()=" + chunks.size() +
                    " but embeddings.size()=" + embeddings.size());
        }

        String collection = props.getWeaviate().getCollection();
        String url = props.getWeaviate().getBaseUrl() + "/v1/batch/objects";
        log.info("[Weaviate] ingestChunks() → POST {} | collection={} | chunks={}", url, collection, chunks.size());

        try {
            String textField  = props.getWeaviate().getTextField();

            ArrayNode objectsArray = objectMapper.createArrayNode();

            for (int i = 0; i < chunks.size(); i++) {
                Chunk c        = chunks.get(i);
                List<Double> v = embeddings.get(i);

                ObjectNode propsNode = objectMapper.createObjectNode();
                propsNode.put(textField,   c.getText());
                propsNode.put("source",    c.getSource());
                propsNode.put("chunk_id",  c.getChunkId());

                ArrayNode vectorNode = objectMapper.createArrayNode();
                for (Double d : v) {
                    vectorNode.add(d);
                }

                ObjectNode obj = objectMapper.createObjectNode();
                obj.put("class", collection);
                obj.set("properties", propsNode);
                obj.set("vector", vectorNode);

                objectsArray.add(obj);
                log.debug("[Weaviate] ingestChunks() chunk[{}] chunkId={} vectorDims={}", i, c.getChunkId(), v.size());
            }

            ObjectNode batchBody = objectMapper.createObjectNode();
            batchBody.set("objects", objectsArray);

            String responseBody = post(url, objectMapper.writeValueAsString(batchBody));
            log.info("[Weaviate] ingestChunks() ← success | ingested={} responseLen={}", chunks.size(), responseBody.length());

        } catch (RuntimeException re) {
            log.error("[Weaviate] ingestChunks() failed: {}", re.getMessage(), re);
            throw re;
        } catch (Exception e) {
            log.error("[Weaviate] ingestChunks() exception: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to ingest chunks into Weaviate", e);
        }
    }

    // -------------------------------------------------------------------------
    // Hybrid search
    // -------------------------------------------------------------------------

    /**
     * Perform a hybrid (BM25 + vector) search using the Weaviate GraphQL API.
     *
     * @param query          Raw text query (used for BM25 side of hybrid).
     * @param queryEmbedding Pre-computed query vector (used for vector side).
     * @return Up to {@code rag.retrieval.top-k} matched documents.
     */
    public List<RetrievedDoc> hybridSearch(String query, List<Double> queryEmbedding) {
        int    topK       = props.getRetrieval().getTopK();
        double alpha      = props.getRetrieval().getHybridAlpha();
        String collection = props.getWeaviate().getCollection();
        String textField  = props.getWeaviate().getTextField();
        String url        = props.getWeaviate().getBaseUrl() + "/v1/graphql";

        log.info("[Weaviate] hybridSearch() → POST {} | collection={} topK={} alpha={} queryLen={} vectorDims={}",
                url, collection, topK, alpha, query.length(), queryEmbedding.size());

        try {
            String vectorStr = queryEmbedding.stream()
                    .map(d -> Double.toString(d))
                    .collect(Collectors.joining(", "));

            String graphql = String.format("""
                    {
                      Get {
                        %s(
                          hybrid: { query: "%s", alpha: %.4f, vector: [%s] }
                          limit: %d
                        ) {
                          %s
                          source
                          chunk_id
                        }
                      }
                    }
                    """,
                    collection,
                    escapeGql(query),
                    alpha,
                    vectorStr,
                    topK,
                    textField);

            ObjectNode body = objectMapper.createObjectNode();
            body.put("query", graphql);

            String responseBody = post(url, objectMapper.writeValueAsString(body));
            log.debug("[Weaviate] hybridSearch() ← raw response: {}", responseBody);

            JsonNode root    = objectMapper.readTree(responseBody);
            JsonNode results = root.path("data").path("Get").path(collection);

            List<RetrievedDoc> docs = new ArrayList<>();
            for (JsonNode n : results) {
                String text    = n.path(textField).asText("");
                String source  = n.path("source").asText("");
                String chunkId = n.path("chunk_id").asText("");
                docs.add(new RetrievedDoc(text, source, chunkId));
            }
            log.info("[Weaviate] hybridSearch() ← returned {} docs", docs.size());
            return docs;

        } catch (RuntimeException re) {
            log.error("[Weaviate] hybridSearch() failed: {}", re.getMessage(), re);
            throw re;
        } catch (Exception e) {
            log.error("[Weaviate] hybridSearch() exception: {}", e.getMessage(), e);
            throw new RuntimeException("Weaviate hybrid search failed", e);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String post(String url, String jsonBody) throws Exception {
        log.debug("[Weaviate] POST {} | bodyLen={}", url, jsonBody.length());
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json");

        String apiKey = props.getWeaviate().getApiKey();
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }

        HttpRequest request = builder
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        log.debug("[Weaviate] POST {} ← status={}", url, response.statusCode());

        if (response.statusCode() >= 300) {
            log.error("[Weaviate] POST {} failed | status={} | body={}", url, response.statusCode(), response.body());
            throw new RuntimeException(
                    "Weaviate request to " + url +
                    " failed with status " + response.statusCode() +
                    ": " + response.body());
        }
        return response.body();
    }

    private int getStatus(String url) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET();

        String apiKey = props.getWeaviate().getApiKey();
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }

        HttpResponse<String> response =
                httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return response.statusCode();
    }

    /** Minimal escaping for inline GraphQL string values. */
    private String escapeGql(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", " ")
                .replace("\r", "");
    }
}
