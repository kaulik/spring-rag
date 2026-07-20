package com.mycompany.orchestrator.memory.summary;

import com.mycompany.orchestrator.common.config.RagProperties;
import com.mycompany.orchestrator.memory.ConversationMemory;
import com.mycompany.orchestrator.memory.Turn;
import com.mycompany.orchestrator.model.LlmCalls;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Compacts a conversation's recentTurns into one summary Turn via a small
 * Ollama model, triggered by AgentOrchestratorService before the orchestrator
 * graph runs whenever the accumulated history text gets too large. Fail-open
 * throughout: any failure (LLM or the Redis write) returns the original,
 * un-summarized turns rather than blocking or failing the request — same
 * discipline as AgentNodes.route()'s classifier fallback and
 * StockApiTools.resolveStockId()'s fallback.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PromptSummarizerService {

    private final RagProperties ragProperties;
    private final SummarizerProperties summarizerProperties;
    private final LlmCalls ollamaCalls;
    private final ConversationMemory conversationMemory;
    private final ObservationRegistry observationRegistry;
    private final MeterRegistry meterRegistry;

    public List<Turn> maybeSummarize(String conversationId, List<Turn> recentTurns) {
        int totalChars = recentTurns.stream()
                .mapToInt(t -> t.text() == null ? 0 : t.text().length())
                .sum();
        if (totalChars < summarizerProperties.getTriggerChars()) {
            return recentTurns;
        }

        String model = (summarizerProperties.getModel() != null && !summarizerProperties.getModel().isBlank())
                ? summarizerProperties.getModel()
                : ragProperties.getOllama().getChatModel();
        log.info("[Summarizer] triggering conversationId={} turns={} totalChars={} threshold={} model={}",
                conversationId, recentTurns.size(), totalChars, summarizerProperties.getTriggerChars(), model);

        return Observation.createNotStarted("agent.summarize", observationRegistry).observe(() -> {
            try {
                String rendered = render(recentTurns);
                String summary = ollamaCalls.chat(SummarizerPrompts.SYSTEM_PROMPT, rendered, model, "summarize");
                List<Turn> replaced = List.of(new Turn(Turn.ROLE_SUMMARY, summary, Instant.now().toEpochMilli()));
                conversationMemory.replace(conversationId, replaced);
                meterRegistry.counter("agent.summarize.calls", "outcome", "success").increment();
                log.info("[Summarizer] completed conversationId={} summaryLen={}",
                        conversationId, summary == null ? 0 : summary.length());
                return replaced;
            } catch (Exception e) {
                meterRegistry.counter("agent.summarize.calls", "outcome", "error").increment();
                log.warn("[Summarizer] failed for conversationId={}, continuing with un-summarized history: {}",
                        conversationId, e.getMessage());
                return recentTurns;
            }
        });
    }

    private String render(List<Turn> turns) {
        return turns.stream()
                .map(t -> t.role() + ": " + t.text())
                .collect(Collectors.joining("\n"));
    }
}
