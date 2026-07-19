package com.example.rag.agent.graph;

import lombok.RequiredArgsConstructor;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;

import java.util.Map;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * Builds the orchestrator graph: LLM-driven routing (route node's first intent
 * dereferenced by a conditional edge, same addConditionalEdges idiom as the
 * pipeline's rerankRoute) to a context producer, then a single terminal
 * generalChat LLM node for every flow. The stock agent's own tool-calling loop
 * (ChatClient + ToolCallingAdvisor) is internal to its node.
 *
 * Linear, acyclic (no reroute):
 *
 *   START -> route -> (conditional on first intent)
 *              +-- KNOWLEDGE_BASE -> knowledgeBase --+
 *              +-- STOCKS         -> stockAgent    --+--> generalChat -> END
 *              +-- GENERAL --------------------------+
 */
@RequiredArgsConstructor
public class OrchestratorGraphFactory {

    private final AgentNodes nodes;
    private final BaseCheckpointSaver checkpointSaver;

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
                .addEdge("knowledgeBase", "generalChat")
                .addEdge("stockAgent", "generalChat")
                .addEdge("generalChat", END)
                .compile(CompileConfig.builder()
                        .checkpointSaver(checkpointSaver)
                        .build());
    }
}
