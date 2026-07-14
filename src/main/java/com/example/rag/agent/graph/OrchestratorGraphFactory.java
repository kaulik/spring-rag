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
 * Builds the orchestrator graph — a single LangGraph4j graph doing both
 * jobs: LLM-driven routing (route node's classification dereferenced by a
 * conditional edge, same addConditionalEdges idiom as the pipeline's
 * rerankRoute) AND, for the STOCKS branch, a native cyclic tool-calling
 * loop (stockAgentStep ⇄ stockToolsStep) instead of delegating looping to
 * Spring AI's internal tool execution.
 *
 *   START -> route -> (conditional on intent)
 *              +-- KNOWLEDGE_BASE -> knowledgeBase -> END
 *              +-- STOCKS         -> stockAgentStep -> (conditional on stockLoopRoute)
 *              |                        ^                  +-- tools -> stockToolsStep --+
 *              |                        +-------------------------------------------------+
 *              |                                            +-- done -> END
 *              +-- GENERAL        -> generalChat  -> END
 */
@RequiredArgsConstructor
public class OrchestratorGraphFactory {

    /**
     * Coarse backstop across the WHOLE graph (route + up to
     * AgentNodes.STOCK_MAX_ITERATIONS stockAgentStep/stockToolsStep pairs),
     * on top of the per-loop cap enforced inside stockAgentStep itself —
     * protects against a bug in that primary cap, not a substitute for it.
     */
    private static final int MAX_GRAPH_ITERATIONS = 20;

    private final AgentNodes nodes;

    public CompiledGraph<OrchestratorState> buildOrchestratorGraph() throws GraphStateException {
        CompiledGraph<OrchestratorState> graph = new StateGraph<>(OrchestratorState::new)
                .addNode("route", node_async(nodes::route))
                .addNode("knowledgeBase", node_async(nodes::knowledgeBase))
                .addNode("stockAgentStep", node_async(nodes::stockAgentStep))
                .addNode("stockToolsStep", node_async(nodes::stockToolsStep))
                .addNode("generalChat", node_async(nodes::generalChat))
                .addEdge(START, "route")
                .addConditionalEdges("route", edge_async(nodes::intentRoute),
                        Map.of(Intent.KNOWLEDGE_BASE.name(), "knowledgeBase",
                               Intent.STOCKS.name(), "stockAgentStep",
                               Intent.GENERAL.name(), "generalChat"))
                .addConditionalEdges("stockAgentStep", edge_async(nodes::stockLoopRoute),
                        Map.of("tools", "stockToolsStep",
                               "done", END))
                .addEdge("stockToolsStep", "stockAgentStep")
                .addEdge("knowledgeBase", END)
                .addEdge("generalChat", END)
                .compile();
        graph.setMaxIterations(MAX_GRAPH_ITERATIONS);
        return graph;
    }
}
