package com.example.rag.v2.graph;

import com.example.rag.service.ChunkingService.Chunk;
import org.bsc.langgraph4j.state.AgentState;

import java.util.List;
import java.util.Map;

/** State flowing through the v2 ingestion graph: chunk → embed → store. */
public class IngestState extends AgentState {

    public IngestState(Map<String, Object> initData) {
        super(initData);
    }

    public String text() {
        return this.<String>value("text").orElse("");
    }

    public String source() {
        return this.<String>value("source").orElse("frontend");
    }

    public List<Chunk> chunks() {
        return this.<List<Chunk>>value("chunks").orElse(List.of());
    }

    public List<List<Double>> embeddings() {
        return this.<List<List<Double>>>value("embeddings").orElse(List.of());
    }

    public int ingestedCount() {
        return this.<Integer>value("ingestedCount").orElse(0);
    }
}
