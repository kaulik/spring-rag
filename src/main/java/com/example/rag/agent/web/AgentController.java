package com.example.rag.agent.web;

import com.example.rag.agent.AgentOrchestratorService;
import com.example.rag.agent.AgentOrchestratorService.AgentResult;
import com.example.rag.security.InputGuardrailService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Multi-agent endpoint. Shares the /api/v2 base path with
 * RagPipelineController (guardrail filters already cover /api/*) but lives
 * in the agent package — the pipeline package stays agent-free.
 */
@Slf4j
@RestController
@RequestMapping("/api/v2")
@RequiredArgsConstructor
public class AgentController {

    private final AgentOrchestratorService orchestratorService;
    private final InputGuardrailService inputGuardrailService;

    @Data
    public static class AgentRequest {
        @NotBlank(message = "query must not be blank")
        private String query;

        /** Optional — omit to start a new conversation. */
        private String conversationId;
    }

    @Data
    public static class AgentResponse {
        private String conversationId;
        private String requestId;
        private String intent;
        private String agent;
        private String answer;
    }

    @PostMapping("/agent")
    public ResponseEntity<AgentResponse> agent(@Valid @RequestBody AgentRequest req) {
        inputGuardrailService.validateQuery(req.getQuery());
        log.info("POST /api/v2/agent — queryLen={} conversationId={}",
                req.getQuery().length(), req.getConversationId());

        AgentResult result = orchestratorService.handle(req.getQuery(), req.getConversationId());

        AgentResponse resp = new AgentResponse();
        resp.setConversationId(result.conversationId());
        resp.setRequestId(result.requestId());
        resp.setIntent(result.intent());
        resp.setAgent(result.agent());
        resp.setAnswer(result.answer());
        return ResponseEntity.ok(resp);
    }
}
