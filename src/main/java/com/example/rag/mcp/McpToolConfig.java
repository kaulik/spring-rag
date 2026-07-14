package com.example.rag.mcp;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * spring-ai-starter-mcp-server-webmvc auto-detects ToolCallbackProvider
 * beans and registers their callbacks as MCP server tools — same
 * MethodToolCallbackProvider pattern already used for the agent's
 * StockApiTools (client side); this is the mirror on the server side.
 */
@Configuration
public class McpToolConfig {

    @Bean
    public ToolCallbackProvider ragPipelineMcpToolCallbacks(RagPipelineMcpTools tools) {
        return MethodToolCallbackProvider.builder().toolObjects(tools).build();
    }
}
