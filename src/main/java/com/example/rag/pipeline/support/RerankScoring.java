package com.example.rag.pipeline.support;

import com.example.rag.weaviate.WeaviateService.RetrievedDoc;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Rerank prompt construction and score parsing for the v2 pipeline.
 *
 * Deliberate copy of RagService's private rerank prompt/parse logic (see
 * RagService.rerank and RagService.parseScores) so v1 stays byte-identical
 * during the parallel-run phase. Any change here must be mirrored there
 * until v1 is retired.
 */
@Slf4j
public final class RerankScoring {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RerankScoring() {
    }

    public static String buildPrompt(String query, List<RetrievedDoc> docs, int previewChars) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a reranking model.\n");
        sb.append("Given a user query and a list of document chunks, ");
        sb.append("assign each chunk a relevance score from 0 to 5 (5 = highly relevant).\n");
        sb.append("Return ONLY valid JSON exactly like this:\n");
        sb.append("{\"scores\": [{\"id\": 1, \"score\": 4.5}, {\"id\": 2, \"score\": 3.0}]}\n\n");
        sb.append("Query: ").append(query).append("\n\n");
        sb.append("Documents:\n");

        for (int i = 0; i < docs.size(); i++) {
            RetrievedDoc d = docs.get(i);
            String preview = d.getText();
            if (preview.length() > previewChars) {
                preview = preview.substring(0, previewChars) + "...";
            }
            sb.append("[").append(i + 1).append("] ")
              .append(preview.replace("\n", " "))
              .append("\n");
        }
        return sb.toString();
    }

    public static Map<Integer, Double> parseScores(String content) {
        try {
            int start = content.indexOf('{');
            int end   = content.lastIndexOf('}');
            if (start < 0 || end <= start) return Map.of();

            String json = content.substring(start, end + 1);
            JsonNode root = MAPPER.readTree(json);
            Map<Integer, Double> result = new HashMap<>();
            for (JsonNode item : root.path("scores")) {
                int    id    = item.path("id").asInt();
                double score = item.path("score").asDouble();
                result.put(id, score);
            }
            return result;
        } catch (Exception e) {
            log.warn("Could not parse rerank JSON: {}", e.getMessage());
            return Map.of();
        }
    }
}
