package com.example.rag.v2.graph;

import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;

import java.util.Map;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * Builds the two v2 graphs. Plain class (no Spring annotations) so graph
 * wiring is unit-testable without a context; RagV2GraphConfig exposes the
 * compiled graphs as beans.
 */
public class RagV2GraphFactory {

    private final RagV2Nodes nodes;

    public RagV2GraphFactory(RagV2Nodes nodes) {
        this.nodes = nodes;
    }

    /** START → chunk → embedChunks → store → END */
    public CompiledGraph<IngestState> buildIngestGraph() throws GraphStateException {
        return new StateGraph<>(IngestState::new)
                .addNode("chunk", node_async(nodes::chunk))
                .addNode("embedChunks", node_async(nodes::embedChunks))
                .addNode("store", node_async(nodes::store))
                .addEdge(START, "chunk")
                .addEdge("chunk", "embedChunks")
                .addEdge("embedChunks", "store")
                .addEdge("store", END)
                .compile();
    }

    /**
     * START → embedQuery → retrieve → sanitize
     *   → (rerank enabled and docs present ? rerank → generate : generate) → END
     */
    public CompiledGraph<InferenceState> buildInferenceGraph() throws GraphStateException {
        return new StateGraph<>(InferenceState::new)
                .addNode("embedQuery", node_async(nodes::embedQuery))
                .addNode("retrieve", node_async(nodes::retrieve))
                .addNode("sanitize", node_async(nodes::sanitize))
                .addNode("rerank", node_async(nodes::rerank))
                .addNode("generate", node_async(nodes::generate))
                .addEdge(START, "embedQuery")
                .addEdge("embedQuery", "retrieve")
                .addEdge("retrieve", "sanitize")
                .addConditionalEdges("sanitize", edge_async(nodes::rerankRoute),
                        Map.of("rerank", "rerank", "skip", "generate"))
                .addEdge("rerank", "generate")
                .addEdge("generate", END)
                .compile();
    }
}
