package com.mycompany.orchestrator.agent;

import com.mycompany.orchestrator.agent.graph.OrchestratorState;
import com.mycompany.orchestrator.memory.ConversationMemory;
import com.mycompany.orchestrator.memory.TaskStore;
import com.mycompany.orchestrator.memory.Turn;
import com.mycompany.orchestrator.memory.summary.PromptSummarizerService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.RunnableConfig;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Facade over the orchestrator graph — thin I/O shell (ids, conversational
 * memory, task state, metrics); all reasoning flow lives in the graph.
 */
@Slf4j
@Service
public class AgentOrchestratorService {

    private final CompiledGraph<OrchestratorState> orchestratorGraph;
    private final ConversationMemory conversationMemory;
    private final TaskStore taskState;
    private final PromptSummarizerService promptSummarizer;
    private final ObservationRegistry observationRegistry;
    private final MeterRegistry meterRegistry;

    public AgentOrchestratorService(@Qualifier("orchestratorGraph") CompiledGraph<OrchestratorState> orchestratorGraph,
                                    ConversationMemory conversationMemory,
                                    TaskStore taskState,
                                    PromptSummarizerService promptSummarizer,
                                    ObservationRegistry observationRegistry,
                                    MeterRegistry meterRegistry) {
        this.orchestratorGraph = orchestratorGraph;
        this.conversationMemory = conversationMemory;
        this.taskState = taskState;
        this.promptSummarizer = promptSummarizer;
        this.observationRegistry = observationRegistry;
        this.meterRegistry = meterRegistry;
    }

    public record AgentResult(String conversationId, String requestId,
                              String intent, String agent, String answer) {}

    public AgentResult handle(String question, String conversationIdOrNull) {
        String conversationId = (conversationIdOrNull == null || conversationIdOrNull.isBlank())
                ? UUID.randomUUID().toString()
                : conversationIdOrNull.trim();
        String requestId = UUID.randomUUID().toString();
        long startedAt = System.currentTimeMillis();

        Observation obs = Observation.createNotStarted("agent.orchestrate", observationRegistry).start();
        log.info("[Orchestrator] request received requestId={} conversationId={} questionLen={}",
                requestId, conversationId, question == null ? 0 : question.length());
        taskState.start(requestId, conversationId);
        try {
            List<Turn> recentTurns = conversationMemory.recentTurns(conversationId);
            log.info("[Orchestrator] loaded {} recent turn(s) requestId={} conversationId={}",
                    recentTurns.size(), requestId, conversationId);
            recentTurns = promptSummarizer.maybeSummarize(conversationId, recentTurns);

            log.info("[Orchestrator] invoking graph requestId={} conversationId={}", requestId, conversationId);
            OrchestratorState state = orchestratorGraph
                    .invoke(Map.of(
                            "question", question,
                            "requestId", requestId,
                            "recentTurns", recentTurns),
                            RunnableConfig.builder().threadId(conversationId).build())
                    .orElseThrow(() -> new IllegalStateException("Orchestrator graph produced no final state"));

            obs.lowCardinalityKeyValue("intent", state.intent());
            obs.lowCardinalityKeyValue("agent", state.agentUsed());
            obs.lowCardinalityKeyValue("outcome", "success");
            meterRegistry.counter("agent.requests",
                    "intent", state.intent(), "agent", state.agentUsed(), "outcome", "success").increment();

            long now = Instant.now().toEpochMilli();
            conversationMemory.append(conversationId, new Turn(Turn.ROLE_USER, question, now));
            conversationMemory.append(conversationId, new Turn(Turn.ROLE_ASSISTANT, state.answer(), now));
            taskState.done(requestId);

            long elapsedMs = System.currentTimeMillis() - startedAt;
            log.info("[Orchestrator] request completed requestId={} conversationId={} intent={} agent={} "
                    + "answerLen={} elapsedMs={}",
                    requestId, conversationId, state.intent(), state.agentUsed(),
                    state.answer() == null ? 0 : state.answer().length(), elapsedMs);
            return new AgentResult(conversationId, requestId, state.intent(), state.agentUsed(), state.answer());

        } catch (Exception e) {
            long elapsedMs = System.currentTimeMillis() - startedAt;
            obs.lowCardinalityKeyValue("outcome", "error");
            obs.error(e);
            meterRegistry.counter("agent.requests",
                    "intent", "unknown", "agent", "unknown", "outcome", "error").increment();
            taskState.failed(requestId, e.getMessage());
            log.error("[Orchestrator] request failed requestId={} conversationId={} elapsedMs={}: {}",
                    requestId, conversationId, elapsedMs, e.getMessage(), e);
            throw e;
        } finally {
            obs.stop();
        }
    }
}
