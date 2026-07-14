package com.example.rag.pipeline.graph;

import com.example.rag.weaviate.WeaviateService.RetrievedDoc;
import org.bsc.langgraph4j.state.AgentState;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * State flowing through the v2 inference graph:
 * embedQuery → retrieve → sanitize → (rerank?) → generate.
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

    /** Present only after the rerank node (or generate's fallback truncation) ran. */
    public Optional<List<RetrievedDoc>> reranked() {
        return this.value("reranked");
    }

    public String answer() {
        return this.<String>value("answer").orElse("");
    }
}
