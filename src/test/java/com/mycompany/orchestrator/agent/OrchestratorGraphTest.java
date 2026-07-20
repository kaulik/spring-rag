package com.mycompany.orchestrator.agent;

import com.mycompany.orchestrator.agent.config.AgentProperties;
import com.mycompany.orchestrator.agent.graph.AgentNodes;
import com.mycompany.orchestrator.agent.graph.OrchestratorGraphFactory;
import com.mycompany.orchestrator.agent.graph.OrchestratorState;
import com.mycompany.orchestrator.common.service.ResponseSanitizer;
import com.mycompany.orchestrator.event.kafka.RagEventPublisher;
import com.mycompany.orchestrator.memory.TaskStore;
import com.mycompany.orchestrator.memory.Turn;
import com.mycompany.orchestrator.tool.stock.StockApiTools;
import com.mycompany.orchestrator.tool.stock.config.StockToolProperties;
import com.mycompany.orchestrator.common.config.RagProperties;
import com.mycompany.orchestrator.model.ollama.OllamaLlmCalls;
import com.mycompany.orchestrator.rag.service.RagPipelineService;
import com.mycompany.orchestrator.rag.service.RagPipelineService.RetrieveResult;
import com.mycompany.orchestrator.vectorstore.RetrievedDoc;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.RunnableConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
 * Orchestrator graph wiring tests — no Spring context, models/services mocked.
 * Locks the routing contract: the router's first intent picks the branch;
 * knowledgeBase and stockAgent produce CONTEXT, and generalChat is the single
 * terminal LLM node that produces the final answer for every flow (so every
 * flow makes one final generalChat chat call). The stock agent's own
 * tool-calling loop (ChatClient + ToolCallingAdvisor) always bottoms out in
 * the same mocked ChatModel.call(Prompt).
 */
class OrchestratorGraphTest {

    private ChatModel chatModel;
    private RagPipelineService ragPipelineService;
    private TaskStore taskStateRepository;
    private ResponseSanitizer responseSanitizer;
    private RagEventPublisher ragEventPublisher;
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
        responseSanitizer = mock(ResponseSanitizer.class);
        when(responseSanitizer.sanitize(any())).thenAnswer(inv -> inv.getArgument(0));
        ragEventPublisher = mock(RagEventPublisher.class);
        OllamaLlmCalls ollamaCalls = new OllamaLlmCalls(chatModel, embeddingModel, new SimpleMeterRegistry());
        StockApiTools stockApiTools = new StockApiTools(
                ragProps, stockToolProps, ObservationRegistry.create(), new SimpleMeterRegistry(),
                taskStateRepository, ollamaCalls, "");

        AgentNodes nodes = new AgentNodes(
                ragProps, agentProps, stockToolProps, ObservationRegistry.create(),
                ollamaCalls, chatModel, ragPipelineService, responseSanitizer, ragEventPublisher,
                taskStateRepository, stockApiTools);
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

    /** First chat call = router; last = the terminal generalChat synthesis. */
    private void mockChat(String routerReply, String generalReply) {
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(chatResponse(routerReply))
                .thenReturn(chatResponse(generalReply));
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
    void directGeneralFlowAnswersViaGeneralChat() throws Exception {
        // router -> GENERAL routes straight to generalChat: router call + generalChat call.
        mockChat("GENERAL", "Hello again!");

        OrchestratorState state = invoke("how are you?");

        assertEquals("GENERAL", state.intent());
        assertEquals("general", state.agentUsed());
        assertEquals("Hello again!", state.answer());
        verify(chatModel, times(2)).call(any(Prompt.class));
        verify(ragEventPublisher, never()).publish(any(), any(), any(), any());
    }

    @Test
    void nonEmptyRecentTurnsSurviveGraphStateCloning() throws Exception {
        // Regression test for a real production NotSerializableException:
        // CompiledGraph.cloneState() clones OrchestratorState via plain Java
        // serialization on every node transition. Only a real, non-empty
        // recentTurns list with actual Turn objects exercises this.
        mockChat("GENERAL", "Hello again!");
        List<Turn> history = List.of(
                new Turn(Turn.ROLE_USER, "hi", 1L),
                new Turn(Turn.ROLE_ASSISTANT, "hello", 2L));

        OrchestratorState state = invoke("how are you?", history);

        assertEquals("general", state.agentUsed());
        assertEquals("Hello again!", state.answer());
    }

    @Test
    void stocksIntentRoutesToStockAgentThenGeneralChat() throws Exception {
        // router -> STOCKS -> stockAgent (plain answer, no tools) -> generalChat synth.
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(chatResponse("STOCKS"))
                .thenReturn(chatResponse("Tata Steel is trading at 100."))   // stock agent answer
                .thenReturn(chatResponse("Tata Steel is at 100 today."));    // generalChat synthesis

        OrchestratorState state = invoke("What is the price of Tata Steel?");

        assertEquals("STOCKS", state.intent());
        assertEquals("stock", state.agentUsed(), "agentUsed reflects the routed sub-agent, not generalChat");
        assertEquals("Tata Steel is at 100 today.", state.answer());
        verify(ragPipelineService, never()).retrieveContext(any());
        verify(taskStateRepository).running("req-1", "STOCKS", "stock");
        verify(ragEventPublisher, never()).publish(any(), any(), any(), any());
        verify(chatModel, times(3)).call(any(Prompt.class));
    }

    @Test
    void stockAgentExecutesToolThenGeneralChatSynthesizes() throws Exception {
        // router(1); stock round 1 -> tool call(2); searchStockSymbol ticker
        // resolution(3); stock round 2 -> final(4); terminal generalChat(5).
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(chatResponse("STOCKS"))
                .thenReturn(toolCallResponse("searchStockSymbol", "{\"query\":\"trending NSE stocks\"}"))
                .thenReturn(chatResponse("AAPL"))
                .thenReturn(chatResponse("Tata Steel and Infosys are trending today."))
                .thenReturn(chatResponse("Trending: Tata Steel and Infosys."));

        OrchestratorState state = invoke("What's trending on NSE?");

        assertEquals("stock", state.agentUsed());
        assertEquals("Trending: Tata Steel and Infosys.", state.answer());
        assertEquals(1, stockServerHits.get(), "the tool must actually have been executed once");
        verify(chatModel, times(5)).call(any(Prompt.class));
        verify(taskStateRepository).incrementToolCalls("req-1");
    }

    @Test
    void stockAgentStopsAtIterationCapWhenModelKeepsRequestingTools() throws Exception {
        // Every response after the router requests another tool call — the loop
        // must not run forever; StockLoopCapAdvisor short-circuits it after
        // StockLoopCapAdvisor.MAX_ITERATIONS (4) raw model calls. (The blanket
        // tool-call stub also answers searchStockSymbol's own ticker-resolution
        // and the terminal generalChat call with tool-call messages, which read
        // as blank text — fine, this test locks the CAP, not the answer text.)
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(chatResponse("STOCKS"))
                .thenAnswer(invocation -> toolCallResponse("searchStockSymbol", "{\"query\":\"trending NSE stocks\"}"));

        OrchestratorState state = invoke("What's trending on NSE?");

        assertEquals("stock", state.agentUsed());
        // router(1) + 4 tool-loop rounds × (model + ticker-resolution) = 1 + 8 = 9,
        // + terminal generalChat(1) = 10. The cap short-circuits the 5th round.
        verify(chatModel, times(10)).call(any(Prompt.class));
        verify(taskStateRepository, times(4)).incrementToolCalls("req-1");
        assertEquals(4, stockServerHits.get(), "exactly MAX_ITERATIONS tool executions, no more");
    }

    @Test
    void knowledgeBaseIntentRetrievesContextThenGeneralChatAnswers() throws Exception {
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(chatResponse("KNOWLEDGE_BASE"))   // router
                .thenReturn(chatResponse("The doc says X."));  // generalChat synthesis
        when(ragPipelineService.retrieveContext("What does the doc say?"))
                .thenReturn(new RetrieveResult(List.of(0.1, 0.2),
                        List.of(new RetrievedDoc("the manual says 30 days", "s1", "c1"))));

        OrchestratorState state = invoke("What does the doc say?");

        assertEquals("KNOWLEDGE_BASE", state.intent());
        assertEquals("knowledge-base", state.agentUsed());
        assertEquals("The doc says X.", state.answer());
        // router + generalChat = 2 calls; the KB node itself makes no LLM call.
        verify(chatModel, times(2)).call(any(Prompt.class));
        verify(ragPipelineService).retrieveContext("What does the doc say?");
        // KB flow publishes exactly one RagEvent with the retrieved context + final answer.
        verify(ragEventPublisher).publish(eq("What does the doc say?"), eq(List.of(0.1, 0.2)), anyList(),
                eq("The doc says X."));
    }

    @Test
    void knowledgeBaseContextIsThreadedIntoGeneralChatPrompt() throws Exception {
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(chatResponse("KNOWLEDGE_BASE"))
                .thenReturn(chatResponse("grounded answer"));
        when(ragPipelineService.retrieveContext(any()))
                .thenReturn(new RetrieveResult(List.of(0.1),
                        List.of(new RetrievedDoc("SECRET_CONTEXT_MARKER", "s1", "c1"))));

        invoke("what does it say?");

        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel, times(2)).call(captor.capture());
        // The 2nd call is generalChat — its user message must carry the retrieved context.
        String generalUserMsg = captor.getAllValues().get(1).getInstructions().get(1).getText();
        assertTrue(generalUserMsg.contains("Reference context:"), generalUserMsg);
        assertTrue(generalUserMsg.contains("SECRET_CONTEXT_MARKER"), generalUserMsg);
    }

    @Test
    void routerEmitsMultipleIntentsAndRoutesOnTheFirst() throws Exception {
        // Multi-line router output: first line drives routing, the full list is saved.
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(chatResponse("STOCKS\nGENERAL"))
                .thenReturn(chatResponse("stock answer"))
                .thenReturn(chatResponse("final answer"));

        OrchestratorState state = invoke("stocks and small talk");

        assertEquals("STOCKS", state.intent(), "routes on the first intent");
        assertEquals(List.of("STOCKS", "GENERAL"), state.intents());
        assertEquals("stock", state.agentUsed());
    }

    @Test
    void agentUsedIsResetBetweenTurnsSoAKbTurnDoesNotLeakIntoALaterGeneralTurn() throws Exception {
        // Regression guard for the per-turn reset in route(): a KB turn sets
        // agentUsed="knowledge-base"; a following direct-GENERAL turn on the same
        // thread must NOT inherit it (which would wrongly re-publish a RagEvent
        // with stale context). Uses one graph + one threadId so the checkpoint's
        // initialState merge carries the prior turn's final state forward.
        when(ragPipelineService.retrieveContext("kb question"))
                .thenReturn(new RetrieveResult(List.of(0.1), List.of(new RetrievedDoc("kb doc", "s", "c"))));
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(chatResponse("KNOWLEDGE_BASE"))   // turn 1 router
                .thenReturn(chatResponse("kb answer"))        // turn 1 generalChat
                .thenReturn(chatResponse("GENERAL"))          // turn 2 router
                .thenReturn(chatResponse("just chatting"));   // turn 2 generalChat

        CompiledGraph<OrchestratorState> graph = factory.buildOrchestratorGraph();
        RunnableConfig cfg = RunnableConfig.builder().threadId("conv-x").build();

        graph.invoke(Map.of("question", "kb question", "requestId", "r1", "recentTurns", List.of()), cfg);
        OrchestratorState turn2 = graph.invoke(
                Map.of("question", "hi there", "requestId", "r2", "recentTurns", List.of()), cfg).orElseThrow();

        assertEquals("general", turn2.agentUsed(), "agentUsed must reset, not leak 'knowledge-base'");
        // Only the KB turn publishes; the GENERAL turn must not.
        verify(ragEventPublisher, times(1)).publish(any(), any(), any(), any());
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
