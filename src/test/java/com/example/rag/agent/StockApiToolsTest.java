package com.example.rag.agent;

import com.example.rag.common.config.RagProperties;
import com.example.rag.memory.TaskStore;
import com.example.rag.model.ollama.OllamaLlmCalls;
import com.example.rag.tool.stock.StockApiTools;
import com.example.rag.tool.stock.config.StockToolProperties;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Exercises StockApiTools against a real (JDK built-in) HTTP server so the
 * path/query shape is verified end-to-end, plus the two failure contracts:
 * ticker-resolution failure falls back to the raw query text (never fails
 * the tool call), and HTTP failure comes back as text, never an exception.
 * apiBaseUrl is the shared /stable root — each tool appends its own path
 * (/search-symbol, /profile), so path assertions matter here.
 */
class StockApiToolsTest {

    private HttpServer server;
    private ChatModel chatModel;
    private StockApiTools tools;
    private TaskStore taskStateRepository;
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final AtomicReference<String> lastQuery = new AtomicReference<>();

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            lastPath.set(exchange.getRequestURI().getPath());
            lastQuery.set(exchange.getRequestURI().getQuery());
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();

        RagProperties ragProps = new RagProperties();
        ragProps.getOllama().setChatModel("chat-model");

        StockToolProperties stockProps = new StockToolProperties();
        stockProps.setApiBaseUrl("http://localhost:" + server.getAddress().getPort());

        chatModel = mock(ChatModel.class);
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        taskStateRepository = mock(TaskStore.class);
        OllamaLlmCalls ollamaCalls = new OllamaLlmCalls(chatModel, embeddingModel, new SimpleMeterRegistry());

        tools = new StockApiTools(ragProps, stockProps, ObservationRegistry.create(), new SimpleMeterRegistry(),
                taskStateRepository, ollamaCalls, "test-key");
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private static ChatResponse chatResponse(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private ToolContext ctx() {
        return new ToolContext(Map.of("requestId", "req-42"));
    }

    @Test
    void resolvesTickerViaChatModelAndSendsItWithApiKey() {
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse("AAPL"));

        String result = tools.searchStockSymbol("Apple", ctx());

        assertEquals("{\"ok\":true}", result);
        assertEquals("/search-symbol", lastPath.get());
        assertTrue(lastQuery.get().contains("query=AAPL"), lastQuery.get());
        assertTrue(lastQuery.get().contains("apikey=test-key"), lastQuery.get());
        verify(taskStateRepository).incrementToolCalls("req-42");
    }

    @Test
    void resolutionFailureFallsBackToRawQueryTextInsteadOfFailingTheCall() {
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("ollama down"));

        String result = assertDoesNotThrow(() -> tools.searchStockSymbol("Tata Steel", ctx()));

        assertEquals("{\"ok\":true}", result);
        assertTrue(lastQuery.get().contains("query=Tata"), lastQuery.get());
    }

    @Test
    void blankResolutionFallsBackToRawQueryText() {
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse("   "));

        tools.searchStockSymbol("Infosys", ctx());

        assertTrue(lastQuery.get().contains("query=Infosys"), lastQuery.get());
    }

    @Test
    void httpFailureReturnsErrorTextNotException() {
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse("AAPL"));
        server.stop(0); // kill the server → connection refused

        String result = assertDoesNotThrow(() -> tools.searchStockSymbol("Apple", ctx()));

        assertTrue(result.startsWith("ERROR:"), "failure must surface as text for the model: " + result);
    }

    @Test
    void nullToolContextIsTolerated() {
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse("AAPL"));

        String result = assertDoesNotThrow(() -> tools.searchStockSymbol("Apple", null));

        assertEquals("{\"ok\":true}", result);
    }

    @Test
    void companyProfileSendsSymbolAndApiKeyWithNoResolutionCall() {
        String result = tools.getCompanyProfile("IBM", ctx());

        assertEquals("{\"ok\":true}", result);
        assertEquals("/profile", lastPath.get());
        assertTrue(lastQuery.get().contains("symbol=IBM"), lastQuery.get());
        assertTrue(lastQuery.get().contains("apikey=test-key"), lastQuery.get());
        verify(taskStateRepository).incrementToolCalls("req-42");
        // getCompanyProfile takes an already-resolved ticker directly, unlike
        // searchStockSymbol — no chat-model call should happen for it.
        verifyNoInteractions(chatModel);
    }

    @Test
    void companyProfileHttpFailureReturnsErrorTextNotException() {
        server.stop(0);

        String result = assertDoesNotThrow(() -> tools.getCompanyProfile("IBM", ctx()));

        assertTrue(result.startsWith("ERROR:"), "failure must surface as text for the model: " + result);
    }

    @Test
    void oversizedResponseIsTruncated() throws Exception {
        // Regression coverage carried over from the old Indian Stock API
        // tool: a large body must not be fed whole back into the model.
        // Own embedded server since the shared fixture always returns the
        // tiny {"ok":true} body.
        String hugeBody = "x".repeat(345_000);
        HttpServer bigServer = HttpServer.create(new InetSocketAddress(0), 0);
        bigServer.createContext("/", exchange -> {
            byte[] body = hugeBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        bigServer.start();
        try {
            RagProperties ragProps = new RagProperties();
            ragProps.getOllama().setChatModel("chat-model");
            StockToolProperties props = new StockToolProperties();
            props.setApiBaseUrl("http://localhost:" + bigServer.getAddress().getPort());
            EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
            OllamaLlmCalls ollamaCalls = new OllamaLlmCalls(chatModel, embeddingModel, new SimpleMeterRegistry());
            when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse("AAPL"));
            StockApiTools bigTools = new StockApiTools(ragProps, props, ObservationRegistry.create(),
                    new SimpleMeterRegistry(), taskStateRepository, ollamaCalls, "test-key");

            String result = bigTools.searchStockSymbol("Apple", ctx());

            assertTrue(result.length() < hugeBody.length(), "oversized tool result must be truncated");
            assertTrue(result.contains("[truncated, showing 4000 of 345000 chars]"), result);
        } finally {
            bigServer.stop(0);
        }
    }
}
