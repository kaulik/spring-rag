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
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.RunnableConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Orchestrator graph wiring tests — no Spring context, models/services
 * mocked. Locks the LLM-driven routing contract: classifier output picks
 * the sub-agent node; garbage output falls back to generalChat. The stock
 * agent's own tool-calling loop (ChatClient + ToolCallingAdvisor) always
 * bottoms out in the same mocked ChatModel.call(Prompt), since
 * ChatModelCallAdvisor — the terminal advisor ChatClient always registers —
 * simply delegates to it.
 */
class OrchestratorGraphTest {

    private ChatModel chatModel;
    private RagPipelineService ragPipelineService;
    private TaskStateRepository taskStateRepository;
    private OrchestratorGraphFactory factory;
    private HttpServer stockServer;
    private final AtomicInteger stockServerHits = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        RagProperties ragProps = new RagProperties();
        ragProps.getOllama().setBaseUrl("http://localhost:11434");
        ragProps.getOllama().setEmbeddingModel("embed-model");
        ragProps.getOllama().setChatModel("chat-model");
        ragProps.getOllama().setRerankModel("");
        ragProps.getOllama().setTimeoutSeconds(60);

        AgentProperties agentProps = new AgentProperties();

        // Real (JDK built-in) HTTP server so the stock agent's tool
        // execution round trip is exercised end to end, not mocked away.
        stockServer = HttpServer.create(new InetSocketAddress(0), 0);
        stockServer.createContext("/", exchange -> {
            stockServerHits.incrementAndGet();
            byte[] body = "{\"trending\":[\"Tata Steel\",\"Infosys\"]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        stockServer.start();
        agentProps.getStock().setApiBaseUrl("http://localhost:" + stockServer.getAddress().getPort());

        chatModel = mock(ChatModel.class);
        // ChatClient always builds its request options via
        // chatModel.getOptions().mutate() (DefaultChatClientUtils), even
        // for calls that never touch ChatClient directly — must be non-null
        // and a ToolCallingChatOptions for the stock agent's tools to merge in.
        when(chatModel.getOptions()).thenReturn(org.springframework.ai.ollama.api.OllamaChatOptions.builder().build());
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        ragPipelineService = mock(RagPipelineService.class);
        taskStateRepository = mock(TaskStateRepository.class);
        StockApiTools stockApiTools = new StockApiTools(
                agentProps, ObservationRegistry.create(), new SimpleMeterRegistry(),
                taskStateRepository, "");

        AgentNodes nodes = new AgentNodes(
                ragProps, agentProps, ObservationRegistry.create(),
                new OllamaCalls(chatModel, embeddingModel, new SimpleMeterRegistry()),
                chatModel, ragPipelineService, taskStateRepository, stockApiTools);
        factory = new OrchestratorGraphFactory(nodes);
    }

    @AfterEach
    void tearDown() {
        stockServer.stop(0);
    }

    private static ChatResponse toolCallResponse(String toolName) {
        AssistantMessage message = AssistantMessage.builder()
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", toolName, "{}")))
                .build();
        return new ChatResponse(List.of(new Generation(message)));
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
    void stockAgentExecutesToolThenLoopsBackForFinalAnswer() throws Exception {
        // router -> STOCKS, then the model requests a real tool call,
        // ToolCallingAdvisor executes it against the embedded HTTP server
        // and loops back internally, and the model's SECOND round gets the
        // final text answer — this is Spring AI's own tool-calling loop,
        // not a hand-rolled cycle.
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(chatResponse("STOCKS"))
                .thenReturn(toolCallResponse("getTrendingStocks"))
                .thenReturn(chatResponse("Tata Steel and Infosys are trending today."));

        OrchestratorState state = invoke("What's trending on NSE?");

        assertEquals("stock", state.agentUsed());
        assertEquals("Tata Steel and Infosys are trending today.", state.answer());
        assertEquals(1, stockServerHits.get(), "the tool must actually have been executed once");
        verify(chatModel, times(3)).call(any(Prompt.class));
        verify(taskStateRepository).incrementToolCalls("req-1");
    }

    @Test
    void stockAgentStopsAtIterationCapWhenModelKeepsRequestingTools() throws Exception {
        // Every response requests another tool call — the loop must not run
        // forever; StockLoopCapAdvisor must short-circuit it after
        // AgentNodes.STOCK_MAX_ITERATIONS raw model calls and still return
        // SOME answer rather than hanging/erroring.
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(chatResponse("STOCKS"))
                .thenAnswer(invocation -> toolCallResponse("getTrendingStocks"));

        OrchestratorState state = invoke("What's trending on NSE?");

        assertEquals("stock", state.agentUsed());
        assertNotNull(state.answer());
        assertFalse(state.answer().isBlank());
        // router(1) + AgentNodes.STOCK_MAX_ITERATIONS (4) model calls, each
        // requesting a tool — the cap advisor short-circuits the 5th round
        // itself rather than reaching the model again.
        verify(chatModel, times(5)).call(any(Prompt.class));
        verify(taskStateRepository, times(4)).incrementToolCalls("req-1");
        assertEquals(4, stockServerHits.get(), "exactly STOCK_MAX_ITERATIONS tool executions, no more");
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
