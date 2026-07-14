package com.example.rag.pipeline.web;

import com.example.rag.security.InputGuardrailService;
import com.example.rag.pipeline.service.RagPipelineService;
import com.example.rag.pipeline.service.RagPipelineService.RagPipelineResult;
import com.example.rag.weaviate.WeaviateService.RetrievedDoc;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * v2 endpoints backed by the Spring AI + LangGraph4j pipeline. DTOs and JSON
 * contract deliberately mirror RagController (v1) so clients can A/B the two
 * pipelines by switching only the path prefix. Guardrail filters (API key,
 * rate limit, CORS) already cover /api/v2/* via the /api/* patterns.
 */
@Slf4j
@RestController
@RequestMapping("/api/v2")
@RequiredArgsConstructor
public class RagPipelineController {

    private final RagPipelineService ragPipelineService;
    private final InputGuardrailService inputGuardrailService;

    // -------------------------------------------------------------------------
    // Request / Response models (mirror v1)
    // -------------------------------------------------------------------------

    @Data
    public static class QueryRequest {
        @NotBlank(message = "query must not be blank")
        private String query;
    }

    @Data
    public static class QueryWithContextRequest {
        @NotBlank(message = "query must not be blank")
        private String query;

        @NotBlank(message = "contextText must not be blank")
        private String contextText;

        /** Optional label stored as 'source' metadata in Weaviate. */
        private String source;
    }

    @Data
    public static class IngestRequest {
        @NotBlank(message = "contextText must not be blank")
        private String contextText;

        private String source;
    }

    @Data
    public static class IngestResponse {
        private int ingestedChunks;
    }

    @Data
    public static class QueryResponse {
        private String             answer;
        private List<RetrievedDoc> sources;
        private Integer            ingestedChunks;
    }

    // -------------------------------------------------------------------------
    // Endpoints
    // -------------------------------------------------------------------------

    @PostMapping("/query")
    public ResponseEntity<QueryResponse> query(@Valid @RequestBody QueryRequest req) {
        inputGuardrailService.validateQuery(req.getQuery());
        log.info("POST /api/v2/query — queryLen={}", req.getQuery().length());
        RagPipelineResult result = ragPipelineService.answerQuestion(req.getQuery());

        QueryResponse resp = new QueryResponse();
        resp.setAnswer(result.answer());
        resp.setSources(result.sources());
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/ingest")
    public ResponseEntity<IngestResponse> ingest(@Valid @RequestBody IngestRequest req) {
        inputGuardrailService.validateContextText(req.getContextText());
        String source = (req.getSource() == null || req.getSource().isBlank())
                ? "frontend"
                : req.getSource().trim();
        log.info("POST /api/v2/ingest — source='{}', contextLen={}", source, req.getContextText().length());

        int chunks = ragPipelineService.ingestContextText(req.getContextText(), source);

        IngestResponse resp = new IngestResponse();
        resp.setIngestedChunks(chunks);
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/query-with-context")
    public ResponseEntity<QueryResponse> queryWithContext(
            @Valid @RequestBody QueryWithContextRequest req) {

        inputGuardrailService.validateQuery(req.getQuery());
        inputGuardrailService.validateContextText(req.getContextText());
        log.info("POST /api/v2/query-with-context — source='{}', contextLen={}, queryLen={}",
                req.getSource(), req.getContextText().length(), req.getQuery().length());

        String source = (req.getSource() == null || req.getSource().isBlank())
                ? "frontend"
                : req.getSource().trim();

        int chunks = ragPipelineService.ingestContextText(req.getContextText(), source);
        RagPipelineResult result = ragPipelineService.answerQuestion(req.getQuery());

        QueryResponse resp = new QueryResponse();
        resp.setAnswer(result.answer());
        resp.setSources(result.sources());
        resp.setIngestedChunks(chunks);
        return ResponseEntity.ok(resp);
    }
}
