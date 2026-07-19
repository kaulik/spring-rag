package com.example.rag.agent.graph;

import com.example.rag.memory.Turn;
import com.example.rag.vectorstore.RetrievedDoc;
import org.bsc.langgraph4j.state.AgentState;

import java.util.List;
import java.util.Map;

/**
 * State flowing through the orchestrator graph (linear, no cycles):
 * route → (KNOWLEDGE_BASE → knowledgeBase | STOCKS → stockAgent | GENERAL) →
 * generalChat → END. route classifies one-or-more intents (routing on the
 * first); knowledgeBase/stockAgent produce CONTEXT (retrieved chunks / the
 * stock tool-loop answer), and generalChat is the single terminal LLM node
 * that turns that context into the final answer for every flow.
 *
 * NOTE (serialization): context/contextDocs/queryEmbedding are only ever set
 * by knowledgeBase and read by generalChat within the SAME turn (in-memory
 * Java-serialization clone, where RetrievedDoc is Serializable). They must be
 * reset in route() each turn — the Redis checkpoint's Jackson round-trip would
 * otherwise resurrect them as type-erased LinkedHashMaps on the next turn's
 * initialState merge. Same discipline the old rerouteCount reset used.
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

    /** The intent that drives routing — the first of {@link #intents()}. */
    public String intent() {
        return this.<String>value("intent").orElse(Intent.GENERAL.name());
    }

    /** All intents the router emitted, most relevant first; routing uses the first, the rest are informational. */
    public List<String> intents() {
        return this.<List<String>>value("intents").orElse(List.of());
    }

    /** Reference context for generalChat: joined KB chunk text, or the stock agent's answer. Empty for direct GENERAL. */
    public String context() {
        return this.<String>value("context").orElse("");
    }

    /** The retrieved chunks behind {@link #context()} (KB flow only) — used to build the Kafka RagEvent. */
    public List<RetrievedDoc> contextDocs() {
        return this.<List<RetrievedDoc>>value("contextDocs").orElse(List.of());
    }

    /** The query embedding from the KB retrieval (KB flow only) — used to build the Kafka RagEvent. */
    public List<Double> queryEmbedding() {
        return this.<List<Double>>value("queryEmbedding").orElse(List.of());
    }

    public String answer() {
        return this.<String>value("answer").orElse("");
    }

    public String agentUsed() {
        return this.<String>value("agentUsed").orElse("");
    }
}
