package com.mycompany.orchestrator.mcp;

import com.mycompany.orchestrator.common.config.RagProperties;
import com.mycompany.orchestrator.model.LlmCalls;
import com.mycompany.orchestrator.rag.helper.RerankScoring;
import com.mycompany.orchestrator.rag.helper.SystemPrompts;
import com.mycompany.orchestrator.security.InputGuardrailService;
import com.mycompany.orchestrator.common.service.ResponseSanitizer;
import com.mycompany.orchestrator.vectorstore.DocumentStore;
import com.mycompany.orchestrator.vectorstore.RetrievedDoc;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The RAG inference pipeline exposed as four independent MCP tools —
 * embed_query, retrieve, rerank, generate — matching the graph's own node
 * boundaries exactly. Deliberately stateless: an MCP client composes its
 * own pipeline by passing each stage's output into the next, rather than
 * the server holding session state between calls (mirrors the LangGraph4j
 * inferenceGraph's node sequence, just externally drivable one stage at a
 * time). Reuses the exact same beans/prompts/model calls as the graph
 * (LlmCalls, RerankScoring, SystemPrompts) so results are identical
 * whether the pipeline runs via POST /api/v2/query or via MCP.
 *
 * These tools bypass RagPipelineController entirely (a different Spring MVC
 * mapping, not a REST endpoint), so guardrail input validation is applied
 * here explicitly rather than inherited from the controller layer. The MCP
 * transport itself is exposed at /api/mcp (see application config), which
 * — because it starts with "/api/" — is still covered by the existing
 * X-API-Key / rate-limit / CORS servlet filters.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagPipelineMcpTools {

    private final RagProperties props;
    private final DocumentStore documentStore;
    private final InputGuardrailService guardrailService;
    private final ResponseSanitizer responseSanitizer;
    private final ObservationRegistry observationRegistry;
    private final LlmCalls ollamaCalls;

    @Tool(description = "Stage 1 of the RAG pipeline: embed a query string into a vector using the "
            + "configured Ollama embedding model. Feed the returned embedding into the retrieve tool.")
    public EmbedQueryResult embedQuery(
            @ToolParam(description = "The user's question or search text") String query) {
        guardrailService.validateQuery(query);
        List<Double> embedding = Observation.createNotStarted("mcp.embed_query", observationRegistry)
                .observe(() -> ollamaCalls.embed(query, props.getOllama().getEmbeddingModel()));
        log.info("[McpTools] embed_query dims={}", embedding.size());
        return new EmbedQueryResult(embedding);
    }

    @Tool(description = "Stage 2 of the RAG pipeline: hybrid (keyword + vector) search over the "
            + "knowledge base. Requires a pre-computed queryEmbedding from embed_query. Returns "
            + "retrieved chunks — feed them into rerank (optional) or straight into generate.")
    public List<ChunkDto> retrieve(
            @ToolParam(description = "The search query text") String query,
            @ToolParam(description = "Query embedding vector, from embed_query") List<Double> queryEmbedding,
            @ToolParam(description = "Max chunks to return; omit to use the pipeline's configured top-k",
                    required = false) Integer topK) {
        guardrailService.validateQuery(query);
        List<RetrievedDoc> docs = Observation.createNotStarted("mcp.retrieve", observationRegistry)
                .observe(() -> documentStore.hybridSearch(query, queryEmbedding));
        List<RetrievedDoc> sanitized = docs.stream()
                .map(d -> new RetrievedDoc(
                        guardrailService.sanitizeRetrievedChunk(d.getText(), d.getSource()),
                        d.getSource(), d.getChunkId()))
                .collect(Collectors.toList());
        int k = (topK != null && topK > 0) ? Math.min(topK, sanitized.size()) : sanitized.size();
        log.info("[McpTools] retrieve returned {} docs (capped to {})", sanitized.size(), k);
        return sanitized.subList(0, k).stream().map(RagPipelineMcpTools::toDto).collect(Collectors.toList());
    }

    @Tool(description = "Stage 3 (optional) of the RAG pipeline: score and reorder chunks by "
            + "relevance to the query using the LLM reranker. Skip this tool and pass retrieve's "
            + "chunks straight to generate if reranking isn't needed.")
    public List<ChunkDto> rerank(
            @ToolParam(description = "The search query text") String query,
            @ToolParam(description = "Chunks to rerank, from retrieve") List<ChunkDto> chunks,
            @ToolParam(description = "Max chunks to keep after reranking; omit to use the pipeline's "
                    + "configured rerank top-k", required = false) Integer topK) {
        guardrailService.validateQuery(query);
        List<RetrievedDoc> docs = chunks.stream().map(RagPipelineMcpTools::fromDto).collect(Collectors.toList());
        int k = (topK != null && topK > 0)
                ? Math.min(topK, docs.size())
                : Math.max(1, Math.min(props.getRetrieval().getRerankTopK(), docs.size()));
        String prompt = RerankScoring.buildPrompt(query, docs, props.getRetrieval().getRerankPreviewChars());
        int kFinal = k;

        List<RetrievedDoc> reranked = Observation.createNotStarted("mcp.rerank", observationRegistry)
                .lowCardinalityKeyValue("inputDocs", String.valueOf(docs.size()))
                .observe(() -> {
                    try {
                        String response = ollamaCalls.chat(
                                SystemPrompts.RERANK_SYSTEM_PROMPT, prompt, rerankModelName(), "mcp-rerank");
                        Map<Integer, Double> scores = RerankScoring.parseScores(response);
                        if (!scores.isEmpty()) {
                            List<RetrievedDoc> sorted = new ArrayList<>(docs);
                            sorted.sort((a, b) -> Double.compare(
                                    scores.getOrDefault(docs.indexOf(b) + 1, 0.0),
                                    scores.getOrDefault(docs.indexOf(a) + 1, 0.0)));
                            return sorted.subList(0, kFinal);
                        }
                    } catch (Exception e) {
                        log.warn("[McpTools] rerank LLM call failed, falling back to input order: {}", e.getMessage());
                    }
                    return docs.subList(0, kFinal);
                });
        return reranked.stream().map(RagPipelineMcpTools::toDto).collect(Collectors.toList());
    }

    @Tool(description = "Final stage of the RAG pipeline: generate an answer to the query using "
            + "the supplied context chunks (from retrieve or rerank).")
    public GenerateResult generate(
            @ToolParam(description = "The user's question") String query,
            @ToolParam(description = "Context chunks to answer from, from retrieve or rerank") List<ChunkDto> chunks) {
        guardrailService.validateQuery(query);
        String context = chunks.stream().map(ChunkDto::text).collect(Collectors.joining("\n\n"));
        String prompt = SystemPrompts.answerUserPrompt(context, query);

        String raw = Observation.createNotStarted("mcp.generate", observationRegistry)
                .observe(() -> ollamaCalls.chat(
                        SystemPrompts.ANSWER_SYSTEM_PROMPT, prompt, props.getOllama().getChatModel(), "mcp-generate"));
        String answer = responseSanitizer.sanitize(raw);
        log.info("[McpTools] generate answer len={}", answer.length());
        return new GenerateResult(answer);
    }

    private String rerankModelName() {
        String rerankModel = props.getOllama().getRerankModel();
        return (rerankModel != null && !rerankModel.isBlank()) ? rerankModel : props.getOllama().getChatModel();
    }

    private static ChunkDto toDto(RetrievedDoc doc) {
        return new ChunkDto(doc.getText(), doc.getSource(), doc.getChunkId());
    }

    private static RetrievedDoc fromDto(ChunkDto dto) {
        return new RetrievedDoc(dto.text(), dto.source(), dto.chunkId());
    }
}
