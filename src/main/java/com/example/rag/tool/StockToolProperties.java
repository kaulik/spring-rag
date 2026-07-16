package com.example.rag.tool;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Config for the stock-api tool (config-repo: agents.stock.*). Owns both the
 * tool's endpoint (api-base-url) and the chat model the stock sub-agent runs
 * on (model) — kept together so removing the stock tool means deleting this
 * one class plus StockApiTools, with no dangling config elsewhere. Blank
 * model = fall back to rag.ollama.chat-model at call time.
 */
@Data
@Component
@ConfigurationProperties(prefix = "agents.stock")
public class StockToolProperties {

    private String model = "";
    private String apiBaseUrl = "https://stock.indianapi.in";
}
