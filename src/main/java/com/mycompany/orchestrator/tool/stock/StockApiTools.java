package com.mycompany.orchestrator.tool.stock;

import com.mycompany.orchestrator.common.config.RagProperties;
import com.mycompany.orchestrator.memory.TaskStore;
import com.mycompany.orchestrator.model.LlmCalls;
import com.mycompany.orchestrator.tool.stock.config.StockToolProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The stock sub-agent's tools — Financial Modeling Prep's REST API
 * (financialmodelingprep.com/stable, apikey query param auth) wrapped as
 * Spring AI @Tool methods. apiBaseUrl is the shared /stable root; each tool
 * appends its own path. Each response is parsed down to just the fields the
 * model actually needs (FMP's raw payloads carry many more) — smaller,
 * cheaper, and more reliable than trusting a small local model to pick the
 * right fields out of a large blob itself; if parsing ever fails (unexpected
 * shape, API error payload, ...) it falls back to the raw response text
 * rather than losing the tool result entirely.
 * Failures come back as error TEXT in the tool result, never exceptions:
 * the model should see a failure and adapt, not crash the request.
 * Each call self-instruments (agent.tool span + agent.tool.calls counter)
 * so observability holds regardless of who drives the tool loop, and
 * increments the request's TaskState toolCalls via the ToolContext.
 */
@Slf4j
@Component
public class StockApiTools {

    private final RagProperties ragProperties;
    private final StockToolProperties stockToolProperties;
    private final ObservationRegistry observationRegistry;
    private final MeterRegistry meterRegistry;
    private final TaskStore taskStateRepository;
    private final LlmCalls ollamaCalls;
    private final RestClient restClient;
    private final String apiKey;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public StockApiTools(RagProperties ragProperties,
                         StockToolProperties stockToolProperties,
                         ObservationRegistry observationRegistry,
                         MeterRegistry meterRegistry,
                         TaskStore taskStateRepository,
                         LlmCalls ollamaCalls,
                         @Value("${STOCK_API_KEY:}") String apiKey) {
        this.ragProperties = ragProperties;
        this.stockToolProperties = stockToolProperties;
        this.observationRegistry = observationRegistry;
        this.meterRegistry = meterRegistry;
        this.taskStateRepository = taskStateRepository;
        this.ollamaCalls = ollamaCalls;
        this.apiKey = apiKey;
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        requestFactory.setReadTimeout(Duration.ofSeconds(10));
        this.restClient = RestClient.builder()
                .baseUrl(stockToolProperties.getApiBaseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    @Tool(description = "Look up a stock's ticker symbol and basic match info by company name, "
            + "asset name, or description.")
    public String searchStockSymbol(
            @ToolParam(description = "Company/asset name or description, e.g. 'Apple' or 'Tata Steel'") String query,
            ToolContext toolContext) {
        String stockId = resolveStockId(query);
        return call("searchStockSymbol", toolContext,
                uri -> uri.path("/search-symbol")
                        .queryParam("query", stockId)
                        .queryParam("apikey", apiKey)
                        .build(),
                this::extractSymbolMatches);
    }

    @Tool(description = "Get a company's profile description by ticker symbol. "
            + "Call searchStockSymbol first if you don't already have a confirmed ticker.")
    public String getCompanyProfile(
            @ToolParam(description = "Ticker symbol, e.g. 'AAPL' or 'IBM'") String symbol,
            ToolContext toolContext) {
        return call("getCompanyProfile", toolContext,
                uri -> uri.path("/profile")
                        .queryParam("symbol", symbol)
                        .queryParam("apikey", apiKey)
                        .build(),
                this::extractDescriptions);
    }

    /** /search-symbol response shape — only symbol/name matter to the model, FMP sends more. */
    private record StockSymbolMatch(String symbol, String name) {
    }

    /** /profile response shape — only description matters here, FMP sends many more fields. */
    private record CompanyProfile(String description) {
    }

    private String extractSymbolMatches(String rawJson) throws Exception {
        List<StockSymbolMatch> matches = objectMapper.readValue(rawJson, new TypeReference<>() {
        });
        if (matches.isEmpty()) {
            return "No matching stock symbols found.";
        }
        return objectMapper.writeValueAsString(matches);
    }

    private String extractDescriptions(String rawJson) throws Exception {
        List<CompanyProfile> profiles = objectMapper.readValue(rawJson, new TypeReference<>() {
        });
        String descriptions = profiles.stream()
                .map(CompanyProfile::description)
                .filter(d -> d != null && !d.isBlank())
                .collect(Collectors.joining("\n\n"));
        return descriptions.isBlank() ? "No company profile found for the given symbol." : descriptions;
    }

    /**
     * The search-symbol endpoint takes a ticker, not free text — resolve one
     * via a dedicated LLM call first. Never lets resolution failure fail the
     * tool call: falls back to the raw query text, which the search endpoint
     * can often still match reasonably (same "never crash on an AI-flakiness
     * detour" discipline as AgentNodes.route()'s classifier fallback).
     */
    private String resolveStockId(String query) {
        String model = (stockToolProperties.getModel() != null && !stockToolProperties.getModel().isBlank())
                ? stockToolProperties.getModel()
                : ragProperties.getOllama().getChatModel();
        try {
            String raw = ollamaCalls.chat(StockPrompts.ID_SYSTEM_PROMPT, query, model, "stock-symbol-lookup", 0.0);
            String resolved = raw == null ? "" : raw.trim();
            return resolved.isEmpty() ? query : resolved;
        } catch (Exception e) {
            log.warn("[StockApiTools] stock id resolution failed for query='{}', falling back to raw query: {}",
                    query, e.getMessage());
            return query;
        }
    }

    /**
     * Some responses run into the hundreds of KB — far more than a small
     * local model needs, or can process in time. Feeding one back whole made
     * the SECOND tool-loop round (the model digesting the tool result) blow
     * past rag.ollama.timeout-seconds entirely, cancelling the request
     * rather than answering slowly.
     */
    private static final int MAX_RESULT_CHARS = 4000;

    /** Parses a raw FMP response body down to just the fields the model needs. */
    @FunctionalInterface
    private interface ResponseExtractor {
        String extract(String rawJson) throws Exception;
    }

    private String call(String tool, ToolContext toolContext,
                        Function<org.springframework.web.util.UriBuilder, java.net.URI> uriFn,
                        ResponseExtractor extractor) {
        incrementTaskToolCalls(toolContext);
        return Observation.createNotStarted("agent.tool", observationRegistry)
                .lowCardinalityKeyValue("tool", tool)
                .observe(() -> {
                    try {
                        log.info("[StockApiTools] {} request={}", tool, uriFn);
                        String body = restClient.get().uri(uriFn).retrieve().body(String.class);
                        meterRegistry.counter("agent.tool.calls", "tool", tool, "outcome", "success").increment();
                        log.info("[StockApiTools] {} ok, body={}, bodyLen={}", tool, body == null ? "" : body, body == null ? 0 : body.length());
                        return truncate(extractOrFallback(tool, body == null ? "" : body, extractor));
                    } catch (Exception e) {
                        meterRegistry.counter("agent.tool.calls", "tool", tool, "outcome", "error").increment();
                        log.warn("[StockApiTools] {} failed: {}", tool, e.getMessage());
                        return "ERROR: the " + tool + " tool call failed: " + e.getMessage()
                                + ". Answer from what you already know, or say the data is unavailable.";
                    }
                });
    }

    /**
     * Extraction runs on the FULL raw body before truncation, not after — an
     * already-truncated body is frequently invalid JSON (arbitrary cut, plus
     * the appended "[truncated...]" suffix), so extracting first means real
     * payloads rarely need truncating at all afterward. Never lets a parsing
     * surprise (unexpected shape, an FMP error payload instead of an array,
     * ...) lose the tool result — falls back to the raw body, same as every
     * other AI/API-flakiness fallback in this class.
     */
    private String extractOrFallback(String tool, String body, ResponseExtractor extractor) {
        try {
            return extractor.extract(body);
        } catch (Exception e) {
            log.warn("[StockApiTools] {} response parsing failed, returning raw response: {}", tool, e.getMessage());
            return body;
        }
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
