package com.example.rag.tool;

import com.example.rag.memory.TaskStore;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.function.Function;

/**
 * The stock sub-agent's specialized tools — the Indian Stock API
 * (stock.indianapi.in, x-api-key auth) wrapped as Spring AI @Tool methods.
 * Failures come back as error TEXT in the tool result, never exceptions:
 * the model should see a failure and adapt, not crash the request.
 * Each call self-instruments (agent.tool span + agent.tool.calls counter)
 * so observability holds regardless of who drives the tool loop, and
 * increments the request's TaskState toolCalls via the ToolContext.
 */
@Slf4j
@Component
public class StockApiTools {

    private final ObservationRegistry observationRegistry;
    private final MeterRegistry meterRegistry;
    private final TaskStore taskStateRepository;
    private final RestClient restClient;

    public StockApiTools(StockToolProperties stockToolProperties,
                         ObservationRegistry observationRegistry,
                         MeterRegistry meterRegistry,
                         TaskStore taskStateRepository,
                         @Value("${STOCK_API_KEY:}") String apiKey) {
        this.observationRegistry = observationRegistry;
        this.meterRegistry = meterRegistry;
        this.taskStateRepository = taskStateRepository;
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        requestFactory.setReadTimeout(Duration.ofSeconds(10));
        this.restClient = RestClient.builder()
                .baseUrl(stockToolProperties.getApiBaseUrl())
                .defaultHeader("x-api-key", apiKey)
                .requestFactory(requestFactory)
                .build();
    }

    @Tool(description = "Get current details (price, fundamentals, company info) for one Indian stock by company name.")
    public String getStockDetails(
            @ToolParam(description = "Company name, e.g. 'Tata Steel'") String name,
            ToolContext toolContext) {
        return call("getStockDetails", toolContext,
                uri -> uri.path("/stock").queryParam("name", name).build());
    }

    @Tool(description = "Get the latest Indian stock market news headlines.")
    public String getStockNews(ToolContext toolContext) {
        return call("getStockNews", toolContext, uri -> uri.path("/news").build());
    }

    @Tool(description = "Get currently trending stocks on the Indian market (NSE/BSE gainers and losers).")
    public String getTrendingStocks(ToolContext toolContext) {
        return call("getTrendingStocks", toolContext, uri -> uri.path("/trending").build());
    }

    @Tool(description = "Get historical price data for an Indian stock. period must be one of: 1m, 6m, 1yr, 3yr, 5yr, 10yr, max. filter must be one of: default, price, pe, sm, evebitda, ptb, mcs.")
    public String getHistoricalData(
            @ToolParam(description = "Company name, e.g. 'Tata Steel'") String stockName,
            @ToolParam(description = "One of: 1m, 6m, 1yr, 3yr, 5yr, 10yr, max") String period,
            @ToolParam(description = "One of: default, price, pe, sm, evebitda, ptb, mcs") String filter,
            ToolContext toolContext) {
        return call("getHistoricalData", toolContext,
                uri -> uri.path("/historical_data")
                        .queryParam("stock_name", stockName)
                        .queryParam("period", period)
                        .queryParam("filter", filter)
                        .build());
    }

    @Tool(description = "Get upcoming and recent IPO (initial public offering) data for the Indian market.")
    public String getIpoData(ToolContext toolContext) {
        return call("getIpoData", toolContext, uri -> uri.path("/ipo").build());
    }

    @Tool(description = "Get a financial statement for an Indian stock. stats selects the statement type, e.g. 'income', 'balance', 'cashflow', 'quarter_results', 'yoy_results'.")
    public String getFinancialStatement(
            @ToolParam(description = "Company name, e.g. 'Tata Steel'") String stockName,
            @ToolParam(description = "Statement type, e.g. 'income', 'balance', 'cashflow'") String stats,
            ToolContext toolContext) {
        return call("getFinancialStatement", toolContext,
                uri -> uri.path("/statement")
                        .queryParam("stock_name", stockName)
                        .queryParam("stats", stats)
                        .build());
    }

    /**
     * Some responses (e.g. getStockDetails) run into the hundreds of KB —
     * far more than a small local model needs, or can process in time.
     * Feeding one back whole made the SECOND tool-loop round (the model
     * digesting the tool result) blow past rag.ollama.timeout-seconds
     * entirely, cancelling the request rather than answering slowly.
     */
    private static final int MAX_RESULT_CHARS = 4000;

    private String call(String tool, ToolContext toolContext,
                        Function<org.springframework.web.util.UriBuilder, java.net.URI> uriFn) {
        incrementTaskToolCalls(toolContext);
        return Observation.createNotStarted("agent.tool", observationRegistry)
                .lowCardinalityKeyValue("tool", tool)
                .observe(() -> {
                    try {
                        String body = restClient.get().uri(uriFn).retrieve().body(String.class);
                        meterRegistry.counter("agent.tool.calls", "tool", tool, "outcome", "success").increment();
                        log.info("[StockApiTools] {} ok, bodyLen={}", tool, body == null ? 0 : body.length());
                        return truncate(body == null ? "" : body);
                    } catch (Exception e) {
                        meterRegistry.counter("agent.tool.calls", "tool", tool, "outcome", "error").increment();
                        log.warn("[StockApiTools] {} failed: {}", tool, e.getMessage());
                        return "ERROR: the " + tool + " tool call failed: " + e.getMessage()
                                + ". Answer from what you already know, or say the data is unavailable.";
                    }
                });
    }

    private static String truncate(String body) {
        if (body.length() <= MAX_RESULT_CHARS) {
            return body;
        }
        return body.substring(0, MAX_RESULT_CHARS)
                + "\n... [truncated, showing " + MAX_RESULT_CHARS + " of " + body.length() + " chars]";
    }

    private void incrementTaskToolCalls(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            return;
        }
        Object requestId = toolContext.getContext().get("requestId");
        if (requestId != null) {
            taskStateRepository.incrementToolCalls(requestId.toString());
        }
    }
}
