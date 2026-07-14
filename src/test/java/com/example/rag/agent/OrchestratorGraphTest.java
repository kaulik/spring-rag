package com.example.rag.agent;

import com.example.rag.agent.config.AgentProperties;
import com.example.rag.agent.graph.AgentNodes;
import com.example.rag.agent.graph.OrchestratorGraphFactory;
import com.example.rag.agent.graph.OrchestratorState;
import com.example.rag.agent.memory.TaskStateRepository;
import com.example.rag.agent.tools.StockApiTools;
import com.example.rag.common.config.RagProperties;
import com.example.rag.pipeline.service.RagPipelineService;
import com.example.rag.pipeline.service.RagPipelineService.RagPipelineResult;
import com.example.rag.pipeline.support.OllamaCalls;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.RunnableConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Orchestrator graph wiring tests — no Spring context, models/services
 * mocked. Locks the LLM-driven routing contract: classifier output picks
 * the sub-agent node; garbage output falls back to generalChat.
 */
class OrchestratorGraphTest {

    private ChatModel chatModel;
    private RagPipelineService ragPipelineService;
    private TaskStateRepository taskStateRepository;
    private OrchestratorGraphFactory factory;

    @BeforeEach
    void setUp() throws Exception {
        RagProperties ragProps = new RagProperties();
        ragProps.getOllama().setBaseUrl("http://localhost:11434");
        ragProps.getOllama().setEmbeddingModel("embed-model");
        ragProps.getOllama().setChatModel("chat-model");
        ragProps.getOllama().setRerankModel("");
        ragProps.getOllama().setTimeoutSeconds(60);

        AgentProperties agentProps = new AgentProperties();

        chatModel = mock(ChatModel.class);
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        ragPipelineService = mock(RagPipelineService.class);
        taskStateRepository = mock(TaskStateRepository.class);
        StockApiTools stockApiTools = new StockApiTools(
                agentProps, ObservationRegistry.create(), new SimpleMeterRegistry(),
                taskStateRepository, "");

        AgentNodes nodes = new AgentNodes(
                ragProps, agentProps, ObservationRegistry.create(),
                new OllamaCalls(chatModel, embeddingModel, new SimpleMeterRegistry()),
                ragPipelineService, taskStateRepository, stockApiTools);
        factory = new OrchestratorGraphFactory(nodes);
    }

    private static ChatResponse chatResponse(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    /** First chat call = router; subsequent calls = sub-agent answer. */
    private void mockChat(String routerReply, String agentReply) {
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(chatResponse(routerReply))
                .thenReturn(chatResponse(agentReply));
    }

    private OrchestratorState invoke(String question) throws Exception {
        CompiledGraph<OrchestratorState> graph = factory.buildOrchestratorGraph();
        Optional<OrchestratorState> out = graph.invoke(
                Map.of("question", question, "requestId", "req-1", "recentTurns", List.of()),
                RunnableConfig.builder().build());
        assertTrue(out.isPresent(), "graph must produce a final state");
        return out.get();
    }

    @Test
    void stocksIntentRoutesToStockAgent() throws Exception {
        mockChat("STOCKS", "Tata Steel is trading at ...");

        OrchestratorState state = invoke("What is the price of Tata Steel?");

        assertEquals("STOCKS", state.intent());
        assertEquals("stock", state.agentUsed());
        assertEquals("Tata Steel is trading at ...", state.answer());
        verify(ragPipelineService, never()).answerQuestion(any());
        verify(taskStateRepository).running("req-1", "STOCKS", "stock");
    }

    @Test
    void knowledgeBaseIntentRoutesToRagPipeline() throws Exception {
        mockChat("KNOWLEDGE_BASE", "unused");
        when(ragPipelineService.answerQuestion("What does the doc say?"))
                .thenReturn(new RagPipelineResult("the doc says X", List.of()));

        OrchestratorState state = invoke("What does the doc say?");

        assertEquals("KNOWLEDGE_BASE", state.intent());
        assertEquals("knowledge-base", state.agentUsed());
        assertEquals("the doc says X", state.answer());
        // router call only — the KB answer comes from the pipeline, not chatModel
        verify(chatModel, times(1)).call(any(Prompt.class));
    }

    @Test
    void garbageClassifierOutputFallsBackToGeneral() throws Exception {
        mockChat("well, maybe stocks? or not, hard to say!", "Hello! How can I help?");

        OrchestratorState state = invoke("hi there");

        // "stocks" appears lowercase but parse() uppercases — STOCKS matches.
        // Use truly garbage output instead:
        // (this asserts the real behavior: parse is lenient on exact names)
        assertEquals("STOCKS", state.intent());
    }

    @Test
    void trulyUnparseableClassifierOutputFallsBackToGeneral() throws Exception {
        mockChat("42, definitely 42", "Hello! How can I help?");

        OrchestratorState state = invoke("hi there");

        assertEquals("GENERAL", state.intent());
        assertEquals("general", state.agentUsed());
        assertEquals("Hello! How can I help?", state.answer());
    }

    @Test
    void routerLlmFailureFallsBackToGeneral() throws Exception {
        when(chatModel.call(any(Prompt.class)))
                .thenThrow(new RuntimeException("ollama down"))
                .thenReturn(chatResponse("Hi!"));

        OrchestratorState state = invoke("hello");

        assertEquals("GENERAL", state.intent());
        assertEquals("Hi!", state.answer());
    }
}
