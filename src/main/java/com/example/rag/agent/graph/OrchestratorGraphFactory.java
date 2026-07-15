package com.example.rag.agent.graph;

import lombok.RequiredArgsConstructor;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;

import java.util.Map;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * Builds the orchestrator graph: LLM-driven routing (route node's
 * classification dereferenced by a conditional edge, same addConditionalEdges
 * idiom as the pipeline's rerankRoute) to one of three sub-agent nodes. The
 * stock agent's own tool-calling loop (ChatClient + ToolCallingAdvisor) is
 * internal to its node — no cyclic edges needed here.
 *
 *   START -> route -> (conditional on intent)
 *              +-- KNOWLEDGE_BASE -> knowledgeBase -> END
 *              +-- STOCKS         -> stockAgent    -> END
 *              +-- GENERAL        -> generalChat   -> END
 */
@RequiredArgsConstructor
public class OrchestratorGraphFactory {

    private final AgentNodes nodes;

    public CompiledGraph<OrchestratorState> buildOrchestratorGraph() throws GraphStateException {
        return new StateGraph<>(OrchestratorState::new)
                .addNode("route", node_async(nodes::route))
                .addNode("knowledgeBase", node_async(nodes::knowledgeBase))
                .addNode("stockAgent", node_async(nodes::stockAgent))
                .addNode("generalChat", node_async(nodes::generalChat))
                .addEdge(START, "route")
                .addConditionalEdges("route", edge_async(nodes::intentRoute),
                        Map.of(Intent.KNOWLEDGE_BASE.name(), "knowledgeBase",
                               Intent.STOCKS.name(), "stockAgent",
                               Intent.GENERAL.name(), "generalChat"))
                .addEdge("knowledgeBase", END)
                .addEdge("stockAgent", END)
                .addEdge("generalChat", END)
                .compile();
    }
}
