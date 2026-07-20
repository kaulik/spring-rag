package com.mycompany.orchestrator.tool.stock;

import com.mycompany.orchestrator.model.LlmCalls;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Caps the stock agent's tool-calling loop and records per-round token
 * usage. Constructed fresh per stockAgent() call (never shared across
 * requests, since its iteration counter is instance state) at an order
 * just past ToolCallingAdvisor.DEFAULT_ORDER, placing it deeper in the
 * chain — ToolCallingAdvisor calls chain.nextCall() once per loop round,
 * so this advisor's adviseCall runs on every round, immediately before
 * the raw model call ChatClient hides from the caller. Once the cap is
 * hit, the call is short-circuited with a synthetic plain-text response
 * instead of reaching the model — a hard stop regardless of what the
 * model would have done, rather than trusting it to honor a hint.
 */
@Slf4j
public final class StockLoopCapAdvisor implements CallAdvisor {

    /**
     * Hard cap on raw model calls within the stock agent's tool-calling
     * loop — Spring AI's ToolCallingAdvisor (which drives the loop) has no
     * built-in iteration limit. Lives here, not in AgentNodes, so tuning it
     * never touches the orchestrator.
     */
    public static final int MAX_ITERATIONS = 4;

    private final int maxIterations;
    private final String model;
    private final String stage;
    private final LlmCalls ollamaCalls;
    private final String requestId;
    private final AtomicInteger iterations = new AtomicInteger(0);

    public StockLoopCapAdvisor(String model, String stage, LlmCalls ollamaCalls, String requestId) {
        this(MAX_ITERATIONS, model, stage, ollamaCalls, requestId);
    }

    StockLoopCapAdvisor(int maxIterations, String model, String stage, LlmCalls ollamaCalls, String requestId) {
        this.maxIterations = maxIterations;
        this.model = model;
        this.stage = stage;
        this.ollamaCalls = ollamaCalls;
        this.requestId = requestId;
    }

    @Override
    public String getName() {
        return "StockLoopCapAdvisor";
    }

    @Override
    public int getOrder() {
        return ToolCallingAdvisor.DEFAULT_ORDER + 1;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        int iteration = iterations.getAndIncrement();
        if (iteration >= maxIterations) {
            log.warn("[Agent:stock] tool-loop cap reached requestId={} iteration={} maxIterations={}",
                    requestId, iteration, maxIterations);
            return capResponse(request);
        }
        log.debug("[Agent:stock] tool-loop round requestId={} iteration={}", requestId, iteration);
        ChatClientResponse response = chain.nextCall(request);
        ollamaCalls.recordTokens(model, stage, response.chatResponse());
        return response;
    }

    private static ChatClientResponse capResponse(ChatClientRequest request) {
        AssistantMessage message = new AssistantMessage(
                "I looked up the requested data but couldn't produce a final summary "
                + "within the allotted tool calls.");
        ChatResponse chatResponse = new ChatResponse(List.of(new Generation(message)));
        return ChatClientResponse.builder()
                .chatResponse(chatResponse)
                .context(request.context())
                .build();
    }
}
