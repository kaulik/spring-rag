package com.mycompany.orchestrator.rag.graph;

import com.mycompany.orchestrator.vectorstore.RetrievedDoc;
import org.bsc.langgraph4j.state.AgentState;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * State flowing through the v2 retrieval graph:
 * embedQuery → retrieve → sanitize → (rerank?). Retrieval-only — generation
 * happens in the orchestrator's generalChat node, so there is no answer here.
 */
public class InferenceState extends AgentState {

    public InferenceState(Map<String, Object> initData) {
        super(initData);
    }

    public String question() {
        return this.<String>value("question").orElse("");
    }

    public List<Double> queryEmbedding() {
        return this.<List<Double>>value("queryEmbedding").orElse(List.of());
    }

    public List<RetrievedDoc> retrieved() {
        return this.<List<RetrievedDoc>>value("retrieved").orElse(List.of());
    }

    public List<RetrievedDoc> sanitized() {
        return this.<List<RetrievedDoc>>value("sanitized").orElse(List.of());
    }

    /** Present only after the rerank node ran; absent on the skip path (see RagPipelineNodes.selectContextDocs). */
    public Optional<List<RetrievedDoc>> reranked() {
        return this.value("reranked");
    }
}
