package com.mycompany.orchestrator.rag.graph;

import com.mycompany.orchestrator.common.config.RagProperties;
import com.mycompany.orchestrator.security.InputGuardrailService;
import com.mycompany.orchestrator.common.service.ChunkingService;
import com.mycompany.orchestrator.common.service.ChunkingService.Chunk;
import com.mycompany.orchestrator.model.LlmCalls;
import com.mycompany.orchestrator.rag.helper.RerankScoring;
import com.mycompany.orchestrator.rag.helper.SystemPrompts;
import com.mycompany.orchestrator.vectorstore.DocumentStore;
import com.mycompany.orchestrator.vectorstore.RetrievedDoc;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** LangGraph4j node implementations for the pipeline graph (ingest + inference). */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagPipelineNodes {

    private final RagProperties props;
    private final ChunkingService chunkingService;
    private final DocumentStore documentStore;
    private final InputGuardrailService guardrailService;
    private final ObservationRegistry observationRegistry;
    private final LlmCalls ollamaCalls;

    // ── Ingest graph nodes ───────────────────────────────────────────────────

    public Map<String, Object> chunk(IngestState state) {
        List<Chunk> chunks = Observation.createNotStarted("rag2.chunk", observationRegistry)
                .observe(() -> chunkingService.chunkText(state.text(), state.source()));
        log.info("[RAGv2] chunk() source='{}' produced {} chunks", state.source(), chunks.size());
        return Map.of("chunks", chunks);
    }

    public Map<String, Object> embedChunks(IngestState state) {
        List<Chunk> chunks = state.chunks();
        if (chunks.isEmpty()) {
            return Map.of("embeddings", List.of());
        }
        List<List<Double>> embeddings = new ArrayList<>(chunks.size());
        Observation obs = Observation.createNotStarted("rag2.embed.batch", observationRegistry)
                .lowCardinalityKeyValue("chunkCount", String.valueOf(chunks.size()))
                .start();
        try {
            for (int i = 0; i < chunks.size(); i++) {
                log.info("[RAGv2] embedChunks() embedding chunk {}/{}", i + 1, chunks.size());
                embeddings.add(ollamaCalls.embed(chunks.get(i).getText(), props.getOllama().getEmbeddingModel()));
            }
        } finally {
            obs.stop();
        }
        return Map.of("embeddings", embeddings);
    }

    public Map<String, Object> store(IngestState state) {
        if (state.chunks().isEmpty()) {
            log.warn("[RAGv2] store() no chunks to ingest for source '{}'", state.source());
            return Map.of("ingestedCount", 0);
        }
        documentStore.ingestChunks(state.chunks(), state.embeddings());
        log.info("[RAGv2] store() complete — {} chunks stored", state.chunks().size());
        return Map.of("ingestedCount", state.chunks().size());
    }

    // ── Inference graph nodes ────────────────────────────────────────────────

    public Map<String, Object> embedQuery(InferenceState state) {
        List<Double> embedding = Observation.createNotStarted("rag2.embed.query", observationRegistry)
                .observe(() -> ollamaCalls.embed(state.question(), props.getOllama().getEmbeddingModel()));
        log.info("[RAGv2] embedQuery() dims={}", embedding.size());
        return Map.of("queryEmbedding", embedding);
    }

    public Map<String, Object> retrieve(InferenceState state) {
        List<RetrievedDoc> retrieved = documentStore.hybridSearch(state.question(), state.queryEmbedding());
        log.info("[RAGv2] retrieve() hybrid search returned {} docs", retrieved.size());
        return Map.of("retrieved", retrieved);
    }

    public Map<String, Object> sanitize(InferenceState state) {
        List<RetrievedDoc> sanitized = state.retrieved().stream()
                .map(doc -> new RetrievedDoc(
                        guardrailService.sanitizeRetrievedChunk(doc.getText(), doc.getSource()),
                        doc.getSource(),
                        doc.getChunkId()))
                .collect(Collectors.toList());
        return Map.of("sanitized", sanitized);
    }

    /**
     * Conditional-edge router: read rerank-enabled per invocation so a config
     * refresh takes effect on the next request without recompiling the graph.
     */
    public String rerankRoute(InferenceState state) {
        boolean rerank = props.getRetrieval().getRerankEnabled() && !state.sanitized().isEmpty();
        return rerank ? "rerank" : "skip";
    }

    public Map<String, Object> rerank(InferenceState state) {
        List<RetrievedDoc> docs = state.sanitized();
        int k = topK(docs);
        String prompt = RerankScoring.buildPrompt(
                state.question(), docs, props.getRetrieval().getRerankPreviewChars());

        List<RetrievedDoc> reranked = Observation
                .createNotStarted("rag2.rerank", observationRegistry)
                .lowCardinalityKeyValue("inputDocs", String.valueOf(docs.size()))
                .observe(() -> {
                    try {
                        String response = ollamaCalls.chat(
                                SystemPrompts.RERANK_SYSTEM_PROMPT, prompt, rerankModelName(), "rerank");
                        Map<Integer, Double> scores = RerankScoring.parseScores(response);
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
                        log.warn("[RAGv2] rerank LLM call failed, falling back to top-{} order: {}", k, e.getMessage());
                    }
                    return docs.subList(0, k);  // fallback: hybrid order, truncated
                });
        return Map.of("reranked", reranked);
    }

    /**
     * Final context selection, formerly the head of the (now-removed) generate
     * node: when rerank was skipped, truncate sanitized docs to top-k. Called by
     * RagPipelineService after the graph runs — generation itself now happens in
     * the orchestrator's generalChat node.
     */
    public List<RetrievedDoc> selectContextDocs(InferenceState state) {
        return state.reranked().orElseGet(() -> {
            List<RetrievedDoc> sanitized = state.sanitized();
            return sanitized.isEmpty() ? sanitized : sanitized.subList(0, topK(sanitized));
        });
    }

    // ── Spring AI call helpers ───────────────────────────────────────────────

    /** Model name resolved per call so config refresh applies immediately. */
    private String rerankModelName() {
        String rerankModel = props.getOllama().getRerankModel();
        return (rerankModel != null && !rerankModel.isBlank())
                ? rerankModel
                : props.getOllama().getChatModel();
    }

    private int topK(List<RetrievedDoc> docs) {
        return Math.max(1, Math.min(props.getRetrieval().getRerankTopK(), docs.size()));
    }
}
