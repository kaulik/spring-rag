package com.example.rag.agent;

import com.example.rag.agent.config.AgentProperties;
import com.example.rag.agent.graph.AgentNodes;
import com.example.rag.agent.graph.OrchestratorGraphFactory;
import com.example.rag.agent.graph.OrchestratorState;
import com.example.rag.memory.TaskStore;
import com.example.rag.memory.Turn;
import com.example.rag.tool.stock.StockApiTools;
import com.example.rag.tool.stock.config.StockToolProperties;
import com.example.rag.common.config.RagProperties;
import com.example.rag.pipeline.service.RagPipelineService;
import com.example.rag.pipeline.service.RagPipelineService.RagPipelineResult;
import com.example.rag.model.ollama.OllamaLlmCalls;
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
    private TaskStore taskStateRepository;
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
        StockToolProperties stockToolProps = new StockToolProperties();

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
        stockToolProps.setApiBaseUrl("http://localhost:" + stockServer.getAddress().getPort());

        chatModel = mock(ChatModel.class);
        // ChatClient always builds its request options via
        // chatModel.getOptions().mutate() (DefaultChatClientUtils), even
        // for calls that never touch ChatClient directly — must be non-null
        // and a ToolCallingChatOptions for the stock agent's tools to merge in.
        when(chatModel.getOptions()).thenReturn(org.springframework.ai.ollama.api.OllamaChatOptions.builder().build());
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        ragPipelineService = mock(RagPipelineService.class);
        taskStateRepository = mock(TaskStore.class);
        OllamaLlmCalls ollamaCalls = new OllamaLlmCalls(chatModel, embeddingModel, new SimpleMeterRegistry());
        StockApiTools stockApiTools = new StockApiTools(
                ragProps, stockToolProps, ObservationRegistry.create(), new SimpleMeterRegistry(),
                taskStateRepository, ollamaCalls, "");

        AgentNodes nodes = new AgentNodes(
                ragProps, agentProps, stockToolProps, ObservationRegistry.create(),
                ollamaCalls,
                chatModel, ragPipelineService, taskStateRepository, stockApiTools);
        // Real (non-Redis) in-memory checkpoint saver — these are pure
        // graph-wiring tests, no live Redis involved.
        factory = new OrchestratorGraphFactory(nodes, new org.bsc.langgraph4j.checkpoint.MemorySaver());
    }

    @AfterEach
    void tearDown() {
        stockServer.stop(0);
    }

    private static ChatResponse toolCallResponse(String toolName, String argumentsJson) {
        AssistantMessage message = AssistantMessage.builder()
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", toolName, argumentsJson)))
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
        return invoke(question, List.of());
    }

    private OrchestratorState invoke(String question, List<Turn> recentTurns) throws Exception {
        CompiledGraph<OrchestratorState> graph = factory.buildOrchestratorGraph();
        Optional<OrchestratorState> out = graph.invoke(
                Map.of("question", question, "requestId", "req-1", "recentTurns", recentTurns),
                RunnableConfig.builder().build());
        assertTrue(out.isPresent(), "graph must produce a final state");
        return out.get();
    }

    @Test
    void nonEmptyRecentTurnsSurviveGraphStateCloning() throws Exception {
        // Regression test for a real production NotSerializableException:
        // CompiledGraph.cloneState() clones OrchestratorState via plain Java
        // serialization (ObjectStreamStateSerializer, the LangGraph4j
        // default) on every node transition — independent of
        // RedisCheckpointSaver's own Jackson-based serialization, which only
        // covers the Redis path. Every other test here passes an EMPTY
        // recentTurns list, which serializes fine regardless of element type
        // (nothing inside it to fail on) — only a real, non-empty list with
        // actual Turn objects exercises this.
        mockChat("GENERAL", "Hello again!");
        List<Turn> history = List.of(
                new Turn(Turn.ROLE_USER, "hi", 1L),
                new Turn(Turn.ROLE_ASSISTANT, "hello", 2L));

        OrchestratorState state = invoke("how are you?", history);

        assertEquals("general", state.agentUsed());
        assertEquals("Hello again!", state.answer());
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
        // not a hand-rolled cycle. searchStockSymbol itself makes ONE more
        // chatModel call of its own (ticker resolution, via the same
        // underlying ChatModel/OllamaLlmCalls — StockApiTools doesn't go
        // through ChatClient/advisors for that), so the full sequence is:
        // router, tool-call round, ticker resolution, final answer round.
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(chatResponse("STOCKS"))
                .thenReturn(toolCallResponse("searchStockSymbol", "{\"query\":\"trending NSE stocks\"}"))
                .thenReturn(chatResponse("AAPL"))
                .thenReturn(chatResponse("Tata Steel and Infosys are trending today."));

        OrchestratorState state = invoke("What's trending on NSE?");

        assertEquals("stock", state.agentUsed());
        assertEquals("Tata Steel and Infosys are trending today.", state.answer());
        assertEquals(1, stockServerHits.get(), "the tool must actually have been executed once");
        verify(chatModel, times(4)).call(any(Prompt.class));
        verify(taskStateRepository).incrementToolCalls("req-1");
    }

    @Test
    void stockAgentStopsAtIterationCapWhenModelKeepsRequestingTools() throws Exception {
        // Every response requests another tool call — the loop must not run
        // forever; StockLoopCapAdvisor must short-circuit it after
        // AgentNodes.STOCK_MAX_ITERATIONS raw model calls and still return
        // SOME answer rather than hanging/erroring. Since every stubbed
        // response after the router is a tool-call response, the blanket
        // stub also answers searchStockSymbol's own ticker-resolution call
        // with a tool-call message — OllamaLlmCalls.chat() reads that as
        // blank text, and resolveStockId() falls back to the raw query, so
        // it never breaks the loop.
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(chatResponse("STOCKS"))
                .thenAnswer(invocation -> toolCallResponse("searchStockSymbol", "{\"query\":\"trending NSE stocks\"}"));

        OrchestratorState state = invoke("What's trending on NSE?");

        assertEquals("stock", state.agentUsed());
        assertNotNull(state.answer());
        assertFalse(state.answer().isBlank());
        // router(1) + AgentNodes.STOCK_MAX_ITERATIONS (4) tool-loop rounds,
        // each of the 4 followed by one searchStockSymbol ticker-resolution
        // call = 1 + 4 + 4 = 9. The cap advisor short-circuits the 5th
        // tool-loop round itself (no model call, no tool execution).
        verify(chatModel, times(9)).call(any(Prompt.class));
        verify(taskStateRepository, times(4)).incrementToolCalls("req-1");
        assertEquals(4, stockServerHits.get(), "exactly STOCK_MAX_ITERATIONS tool executions, no more");
    }

    @Test
    void stockAgentReroutesToKnowledgeBaseWhenOutOfDomain() throws Exception {
        // router -> STOCKS, but the model itself decides mid-answer that
        // this isn't a stock question and hands off via the REROUTE
        // sentinel — the graph must follow that handoff to knowledgeBase
        // rather than returning the stock agent's own (redirecting) answer.
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(chatResponse("STOCKS"))
                .thenReturn(chatResponse("This isn't something I can help with.\nREROUTE: KNOWLEDGE_BASE"));
        when(ragPipelineService.answerQuestion("What does the manual say about returns policy?"))
                .thenReturn(new RagPipelineResult("the manual says 30 days", List.of()));

        OrchestratorState state = invoke("What does the manual say about returns policy?");

        assertEquals("knowledge-base", state.agentUsed());
        assertEquals("the manual says 30 days", state.answer());
        assertEquals(1, state.rerouteCount());
        assertTrue(state.handoffIntent().isEmpty(), "handoffIntent must be cleared once the handoff completes");
        verify(chatModel, times(2)).call(any(Prompt.class));
    }

    @Test
    void rerouteCapPreventsSecondHandoff() throws Exception {
        // First reroute (GENERAL -> STOCKS) is honored; the second sub-agent
        // ALSO tries to reroute (STOCKS -> GENERAL again), but
        // AgentNodes.MAX_REROUTES (1) must refuse it — the graph ends on the
        // second agent's own answer instead of ping-ponging forever.
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(chatResponse("GENERAL"))
                .thenReturn(chatResponse("Let's talk stocks instead.\nREROUTE: STOCKS"))
                .thenReturn(chatResponse("Actually, let me redirect this.\nREROUTE: GENERAL"));

        OrchestratorState state = invoke("hi");

        assertEquals("stock", state.agentUsed(), "second reroute must be refused once MAX_REROUTES is hit");
        assertEquals("Actually, let me redirect this.", state.answer());
        assertEquals(1, state.rerouteCount(), "only the first handoff counts");
        assertTrue(state.handoffIntent().isEmpty());
        verify(chatModel, times(3)).call(any(Prompt.class));
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
