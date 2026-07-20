package com.mycompany.orchestrator.web.controller;

import com.mycompany.orchestrator.rag.service.RagPipelineService;
import com.mycompany.orchestrator.security.InputGuardrailService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * v2 ingest endpoint backed by the Spring AI + LangGraph4j pipeline. Querying
 * is served exclusively by the multi-agent orchestrator (POST /api/v2/agent);
 * the old direct query endpoints were removed once nothing consumed them.
 * Guardrail filters (API key, rate limit, CORS) already cover /api/v2/* via
 * the /api/* patterns.
 */
@Slf4j
@RestController
@RequestMapping("/api/v2")
@RequiredArgsConstructor
public class RagPipelineController {

    private final RagPipelineService ragPipelineService;
    private final InputGuardrailService inputGuardrailService;

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
}
