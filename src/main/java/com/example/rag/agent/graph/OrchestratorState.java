package com.example.rag.agent.graph;

import com.example.rag.agent.memory.Turn;
import org.bsc.langgraph4j.state.AgentState;

import java.util.List;
import java.util.Map;

/**
 * State flowing through the orchestrator graph:
 * route → (LLM-chosen conditional edge) → knowledgeBase | generalChat | END,
 * or → stockAgentStep ⇄ stockToolsStep (LangGraph4j-native cyclic tool-calling
 * loop) → END. The stock* fields only matter while inside that loop.
 *
 * stockConversation holds StockTurn (serializable mirror) records rather
 * than real Spring AI Message objects — LangGraph4j clones state via Java
 * serialization on every node transition, and Message/AssistantMessage/etc.
 * don't implement Serializable. See StockTurn's javadoc.
 */
public class OrchestratorState extends AgentState {

    public OrchestratorState(Map<String, Object> initData) {
        super(initData);
    }

    public String question() {
        return this.<String>value("question").orElse("");
    }

    public String requestId() {
        return this.<String>value("requestId").orElse("");
    }

    public List<Turn> recentTurns() {
        return this.<List<Turn>>value("recentTurns").orElse(List.of());
    }

    public String intent() {
        return this.<String>value("intent").orElse(Intent.GENERAL.name());
    }

    public String answer() {
        return this.<String>value("answer").orElse("");
    }

    public String agentUsed() {
        return this.<String>value("agentUsed").orElse("");
    }

    // ── Stock tool-calling loop state ───────────────────────────────────────

    public List<StockTurn> stockConversation() {
        return this.<List<StockTurn>>value("stockConversation").orElse(List.of());
    }

    public int stockIterations() {
        return this.<Integer>value("stockIterations").orElse(0);
    }

    /** Explicit continue/stop flag written fresh by stockAgentStep every time — never derived from possibly-stale response state. */
    public boolean stockContinue() {
        return this.<Boolean>value("stockContinue").orElse(false);
    }
}
