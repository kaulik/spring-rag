package com.example.rag.web;

import com.example.rag.service.RagService;
import com.example.rag.service.RagService.RagResult;
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
 * Exposes two endpoints:
 *
 *  POST /api/query                 – query already-stored documents
 *  POST /api/query-with-context    – ingest textarea text then query
 */
@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class RagController {

    private final RagService ragService;

    // -------------------------------------------------------------------------
    // Request / Response models
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
    public static class QueryResponse {
        private String            answer;
        private List<RetrievedDoc> sources;
        private Integer           ingestedChunks;
    }

    // -------------------------------------------------------------------------
    // Endpoints
    // -------------------------------------------------------------------------

    /**
     * Query over previously ingested documents.
     */
    @PostMapping("/query")
    public ResponseEntity<QueryResponse> query(@Valid @RequestBody QueryRequest req) {
        log.info("POST /api/query — query='{}'", req.getQuery());
        RagResult result = ragService.answerQuestion(req.getQuery());

        QueryResponse resp = new QueryResponse();
        resp.setAnswer(result.getAnswer());
        resp.setSources(result.getSources());
        return ResponseEntity.ok(resp);
    }

    /**
     * Ingest raw context text (from a frontend text area) and then answer
     * the supplied question over it.
     */
    @PostMapping("/query-with-context")
    public ResponseEntity<QueryResponse> queryWithContext(
            @Valid @RequestBody QueryWithContextRequest req) {

        log.info("POST /api/query-with-context — source='{}', contextLen={}, query='{}'",
                req.getSource(), req.getContextText() != null ? req.getContextText().length() : 0,
                req.getQuery());

        String source = (req.getSource() == null || req.getSource().isBlank())
                ? "frontend"
                : req.getSource().trim();

        int chunks = ragService.ingestContextText(req.getContextText(), source);
        RagResult result = ragService.answerQuestion(req.getQuery());

        QueryResponse resp = new QueryResponse();
        resp.setAnswer(result.getAnswer());
        resp.setSources(result.getSources());
        resp.setIngestedChunks(chunks);
        return ResponseEntity.ok(resp);
    }
}
