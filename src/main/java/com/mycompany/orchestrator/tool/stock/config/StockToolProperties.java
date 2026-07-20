package com.mycompany.orchestrator.tool.stock.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Config for the stock tools (config-repo: agents.stock.*). Owns both the
 * API root (api-base-url — the tools each append their own path, e.g.
 * /search-symbol, /profile) and the chat model the stock sub-agent runs on
 * (model) — kept together so removing stock support means deleting the
 * whole com.mycompany.orchestrator.tool.stock package, with no dangling config
 * elsewhere. Blank model = fall back to rag.ollama.chat-model at call time.
 */
@Data
@Component
@ConfigurationProperties(prefix = "agents.stock")
public class StockToolProperties {

    private String model = "";
    private String apiBaseUrl = "https://financialmodelingprep.com/stable";
}
