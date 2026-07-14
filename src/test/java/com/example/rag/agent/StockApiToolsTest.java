package com.example.rag.agent;

import com.example.rag.agent.config.AgentProperties;
import com.example.rag.agent.memory.TaskStateRepository;
import com.example.rag.agent.tools.StockApiTools;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Exercises StockApiTools against a real (JDK built-in) HTTP server so the
 * URL/query/header shape is verified end-to-end, plus the failure contract:
 * errors come back as text, never exceptions.
 */
class StockApiToolsTest {

    private HttpServer server;
    private StockApiTools tools;
    private TaskStateRepository taskStateRepository;
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final AtomicReference<String> lastQuery = new AtomicReference<>();
    private final AtomicReference<String> lastApiKey = new AtomicReference<>();

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            lastPath.set(exchange.getRequestURI().getPath());
            lastQuery.set(exchange.getRequestURI().getQuery());
            lastApiKey.set(exchange.getRequestHeaders().getFirst("x-api-key"));
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();

        AgentProperties props = new AgentProperties();
        props.getStock().setApiBaseUrl("http://localhost:" + server.getAddress().getPort());
        taskStateRepository = mock(TaskStateRepository.class);
        tools = new StockApiTools(props, ObservationRegistry.create(), new SimpleMeterRegistry(),
                taskStateRepository, "test-key");
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private ToolContext ctx() {
        return new ToolContext(Map.of("requestId", "req-42"));
    }

    @Test
    void stockDetailsSendsNameParamAndApiKey() {
        String result = tools.getStockDetails("Tata Steel", ctx());

        assertEquals("{\"ok\":true}", result);
        assertEquals("/stock", lastPath.get());
        assertTrue(lastQuery.get().contains("name=Tata"));
        assertEquals("test-key", lastApiKey.get());
        verify(taskStateRepository).incrementToolCalls("req-42");
    }

    @Test
    void historicalDataSendsAllThreeParams() {
        tools.getHistoricalData("Infosys", "1yr", "price", ctx());

        assertEquals("/historical_data", lastPath.get());
        String q = lastQuery.get();
        assertTrue(q.contains("stock_name=Infosys"));
        assertTrue(q.contains("period=1yr"));
        assertTrue(q.contains("filter=price"));
    }

    @Test
    void parameterlessToolsHitTheirPaths() {
        tools.getTrendingStocks(ctx());
        assertEquals("/trending", lastPath.get());
        tools.getStockNews(ctx());
        assertEquals("/news", lastPath.get());
        tools.getIpoData(ctx());
        assertEquals("/ipo", lastPath.get());
    }

    @Test
    void statementSendsStockNameAndStats() {
        tools.getFinancialStatement("Wipro", "cashflow", ctx());

        assertEquals("/statement", lastPath.get());
        assertTrue(lastQuery.get().contains("stock_name=Wipro"));
        assertTrue(lastQuery.get().contains("stats=cashflow"));
    }

    @Test
    void httpFailureReturnsErrorTextNotException() {
        server.stop(0); // kill the server → connection refused

        String result = assertDoesNotThrow(() -> tools.getTrendingStocks(ctx()));

        assertTrue(result.startsWith("ERROR:"), "failure must surface as text for the model: " + result);
    }

    @Test
    void nullToolContextIsTolerated() {
        String result = assertDoesNotThrow(() -> tools.getStockNews(null));
        assertEquals("{\"ok\":true}", result);
    }
}
