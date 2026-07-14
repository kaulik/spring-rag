package com.example.rag.agent.graph;

import java.io.Serializable;
import java.util.List;

/**
 * Serializable mirror of one round of the stock tool-calling conversation.
 * LangGraph4j clones state via plain Java serialization on every node
 * transition (org.bsc.langgraph4j.CompiledGraph#cloneState), and Spring
 * AI's Message/AssistantMessage/ToolResponseMessage/ChatResponse/Prompt
 * types do NOT implement Serializable — so none of them can be stored in
 * OrchestratorState directly. AgentNodes converts real Spring AI messages
 * to/from these records at the state boundary; the real objects only ever
 * live within a single node method call.
 */
public sealed interface StockTurn extends Serializable {

    record AssistantTurn(String content, List<ToolCallRecord> toolCalls) implements StockTurn {}

    record ToolResultTurn(List<ToolResponseRecord> responses) implements StockTurn {}

    record ToolCallRecord(String id, String type, String name, String arguments) implements Serializable {}

    record ToolResponseRecord(String id, String name, String responseData) implements Serializable {}
}
