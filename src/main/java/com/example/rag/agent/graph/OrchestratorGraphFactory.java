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
 * Builds the orchestrator graph: LLM-driven routing (route node's
 * classification dereferenced by a conditional edge, same addConditionalEdges
 * idiom as the pipeline's rerankRoute) to one of three sub-agent nodes. The
 * stock agent's own tool-calling loop (ChatClient + ToolCallingAdvisor) is
 * internal to its node — no cyclic edges needed for that.
 *
 * stockAgent and generalChat can each hand off to a DIFFERENT sub-agent once
 * (AgentNodes.MAX_REROUTES), via rerouteEdge reading the handoffIntent a node
 * sets when it judges the question outside its own domain — this is the one
 * genuine cycle in the graph, capped per-node (parseReroute) with a coarse
 * setMaxIterations backstop below as a safety net for a bug in that cap, not
 * a substitute for it. knowledgeBase is reroute-target-only (RagPipelineService
 * has no sentinel-emitting prompt surface), so it always edges straight to END.
 *
 *   START -> route -> (conditional on intent)
 *              +-- KNOWLEDGE_BASE -> knowledgeBase -> END
 *              +-- STOCKS         -> stockAgent    -> (conditional on rerouteEdge)
 *              |                                         +-- KNOWLEDGE_BASE -> knowledgeBase -> END
 *              |                                         +-- GENERAL        -> generalChat   -> (below)
 *              |                                         +-- done           -> END
 *              +-- GENERAL        -> generalChat   -> (conditional on rerouteEdge)
 *                                                        +-- KNOWLEDGE_BASE -> knowledgeBase -> END
 *                                                        +-- STOCKS         -> stockAgent    -> (above)
 *                                                        +-- done           -> END
 */
@RequiredArgsConstructor
public class OrchestratorGraphFactory {

    /**
     * Coarse backstop across the whole graph, on top of the per-node
     * MAX_REROUTES cap enforced inside parseReroute itself — worst case with
     * one reroute hop is 3 node executions (route + 2 sub-agents), this is a
     * generous multiple of that.
     */
    private static final int MAX_GRAPH_ITERATIONS = 10;

    private final AgentNodes nodes;
    private final BaseCheckpointSaver checkpointSaver;

    public CompiledGraph<OrchestratorState> buildOrchestratorGraph() throws GraphStateException {
        CompiledGraph<OrchestratorState> graph = new StateGraph<>(OrchestratorState::new)
                .addNode("route", node_async(nodes::route))
                .addNode("knowledgeBase", node_async(nodes::knowledgeBase))
                .addNode("stockAgent", node_async(nodes::stockAgent))
                .addNode("generalChat", node_async(nodes::generalChat))
                .addEdge(START, "route")
                .addConditionalEdges("route", edge_async(nodes::intentRoute),
                        Map.of(Intent.KNOWLEDGE_BASE.name(), "knowledgeBase",
                               Intent.STOCKS.name(), "stockAgent",
                               Intent.GENERAL.name(), "generalChat"))
                .addConditionalEdges("stockAgent", edge_async(nodes::rerouteEdge),
                        Map.of(Intent.KNOWLEDGE_BASE.name(), "knowledgeBase",
                               Intent.GENERAL.name(), "generalChat",
                               "done", END))
                .addConditionalEdges("generalChat", edge_async(nodes::rerouteEdge),
                        Map.of(Intent.KNOWLEDGE_BASE.name(), "knowledgeBase",
                               Intent.STOCKS.name(), "stockAgent",
                               "done", END))
                .addEdge("knowledgeBase", END)
                .compile(CompileConfig.builder()
                        .checkpointSaver(checkpointSaver)
                        .build());
        graph.setMaxIterations(MAX_GRAPH_ITERATIONS);
        return graph;
    }
}
