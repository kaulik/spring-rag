package com.example.rag.service;

import com.example.rag.config.RagProperties;
import com.example.rag.ollama.OllamaClient;
import com.example.rag.service.ChunkingService.Chunk;
import com.example.rag.weaviate.WeaviateService;
import com.example.rag.weaviate.WeaviateService.RetrievedDoc;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Orchestrates the full Hybrid RAG + LLM-rerank pipeline:
 *
 *  1. ingestContextText()  → chunk → embed → upsert into Weaviate
 *  2. answerQuestion()     → embed query → hybrid search top-10
 *                          → LLM rerank → LLM answer generation
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagService {

    private final RagProperties    props;
    private final ChunkingService  chunkingService;
    private final OllamaClient     ollamaClient;
    private final WeaviateService  weaviateService;
    private final ObjectMapper     objectMapper = new ObjectMapper();

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    @Data
    public static class RagResult {
        private String            answer;
        private List<RetrievedDoc> sources;
        private int               ingestedChunks;
    }

    /**
     * Chunk, embed, and store raw text from a frontend text area.
     *
     * @return number of chunks ingested
     */
    public int ingestContextText(String text, String source) {
        log.info("[RAG] ingestContextText() source='{}' textLen={}", source, text.length());

        List<Chunk> chunks = chunkingService.chunkText(text, source);
        if (chunks.isEmpty()) {
            log.warn("[RAG] ingestContextText() no chunks produced for source '{}'", source);
            return 0;
        }
        log.info("[RAG] ingestContextText() chunked into {} chunks", chunks.size());

        List<List<Double>> embeddings = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            log.info("[RAG] ingestContextText() embedding chunk {}/{}", i + 1, chunks.size());
            embeddings.add(ollamaClient.embed(chunks.get(i).getText()));
        }
        log.info("[RAG] ingestContextText() all embeddings done, sending to Weaviate");

        weaviateService.ingestChunks(chunks, embeddings);
        log.info("[RAG] ingestContextText() complete — {} chunks stored", chunks.size());
        return chunks.size();
    }

    /**
     * Run Hybrid RAG + rerank for a plain question (over already-stored docs).
     */
    public RagResult answerQuestion(String question) {
        log.info("[RAG] answerQuestion() question='{}'", question);

        log.info("[RAG] Step 1/4 — embedding query via Ollama");
        List<Double> queryEmbedding = ollamaClient.embed(question);
        log.info("[RAG] Step 1/4 — query embedding done, dims={}", queryEmbedding.size());

        log.info("[RAG] Step 2/4 — hybrid search in Weaviate");
        List<RetrievedDoc> retrieved = weaviateService.hybridSearch(question, queryEmbedding);
        log.info("[RAG] Step 2/4 — hybrid search returned {} docs", retrieved.size());

        log.info("[RAG] Step 3/4 — reranking {} docs via Ollama", retrieved.size());
        List<RetrievedDoc> reranked = rerank(question, retrieved);
        log.info("[RAG] Step 3/4 — rerank kept {} docs", reranked.size());

        log.info("[RAG] Step 4/4 — generating answer via Ollama");
        String context = reranked.stream()
                .map(RetrievedDoc::getText)
                .collect(Collectors.joining("\n\n"));

        String prompt =
                "You are a helpful AI assistant. " +
                "Use ONLY the context below to answer the question. " +
                "If the answer is not present in the context, say \"I don't know\".\n\n" +
                "Context:\n" + context + "\n\n" +
                "Question: " + question;

        String answer = ollamaClient.chat(prompt);
        log.info("[RAG] Step 4/4 — answer generated, len={}", answer.length());

        RagResult result = new RagResult();
        result.setAnswer(answer);
        result.setSources(reranked);
        result.setIngestedChunks(0);
        return result;
    }

    // -------------------------------------------------------------------------
    // LLM-based reranking
    // -------------------------------------------------------------------------

    private List<RetrievedDoc> rerank(String query, List<RetrievedDoc> docs) {
        if (docs.isEmpty()) return docs;

        int k = Math.max(1, Math.min(props.getRetrieval().getRerankTopK(), docs.size()));

        // Build the scoring prompt
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
            if (preview.length() > 400) {
                preview = preview.substring(0, 400) + "...";
            }
            sb.append("[").append(i + 1).append("] ")
              .append("(source: ").append(d.getSource()).append(") ")
              .append(preview.replace("\n", " "))
              .append("\n");
        }

        try {
            String response = ollamaClient.chat(sb.toString());
            Map<Integer, Double> scores = parseScores(response);

            if (!scores.isEmpty()) {
                List<RetrievedDoc> sorted = new ArrayList<>(docs);
                sorted.sort((a, b) -> {
                    double sA = scores.getOrDefault(docs.indexOf(a) + 1, 0.0);
                    double sB = scores.getOrDefault(docs.indexOf(b) + 1, 0.0);
                    return Double.compare(sB, sA);  // descending
                });
                return sorted.subList(0, k);
            }
        } catch (Exception e) {
            log.warn("Rerank LLM call failed, falling back to top-{} order: {}", k, e.getMessage());
        }

        // Fallback: just truncate
        return docs.subList(0, k);
    }

    private Map<Integer, Double> parseScores(String content) {
        try {
            int start = content.indexOf('{');
            int end   = content.lastIndexOf('}');
            if (start < 0 || end <= start) return Map.of();

            String json = content.substring(start, end + 1);
            JsonNode root = objectMapper.readTree(json);
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
