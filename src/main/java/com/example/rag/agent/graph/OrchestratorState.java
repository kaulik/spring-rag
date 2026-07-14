package com.example.rag.agent.graph;

import com.example.rag.agent.memory.Turn;
import org.bsc.langgraph4j.state.AgentState;

import java.util.List;
import java.util.Map;

/**
 * State flowing through the orchestrator graph:
 * route → (LLM-chosen conditional edge) → knowledgeBase | stockAgent | generalChat.
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
}
