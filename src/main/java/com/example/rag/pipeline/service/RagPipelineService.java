package com.example.rag.pipeline.service;

import com.example.rag.pipeline.graph.IngestState;
import com.example.rag.pipeline.graph.InferenceState;
import com.example.rag.pipeline.graph.RagPipelineNodes;
import com.example.rag.vectorstore.RetrievedDoc;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.RunnableConfig;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Facade over the compiled pipeline graphs (LangGraph4j ingest + inference).
 * The inference graph now stops at retrieval/rerank — no generation, no Kafka.
 * The orchestrator's generalChat node turns the retrieved context into the
 * final answer and publishes the RagEvent.
 */
@Slf4j
@Service
public class RagPipelineService {

    private final CompiledGraph<IngestState> ingestGraph;
    private final CompiledGraph<InferenceState> inferenceGraph;
    private final RagPipelineNodes nodes;
    private final ObservationRegistry observationRegistry;

    public RagPipelineService(@Qualifier("ingestGraph") CompiledGraph<IngestState> ingestGraph,
                        @Qualifier("inferenceGraph") CompiledGraph<InferenceState> inferenceGraph,
                        RagPipelineNodes nodes,
                        ObservationRegistry observationRegistry) {
        this.ingestGraph = ingestGraph;
        this.inferenceGraph = inferenceGraph;
        this.nodes = nodes;
        this.observationRegistry = observationRegistry;
    }

    /** Retrieved context (no generated answer): the query embedding + the final selected chunks. */
    public record RetrieveResult(List<Double> queryEmbedding, List<RetrievedDoc> docs) {}

    /** Chunk, embed, and store raw text. @return number of chunks ingested. */
    public int ingestContextText(String text, String source) {
        return Observation.createNotStarted("rag2.ingest", observationRegistry)
                .lowCardinalityKeyValue("source", source)
                .observe(() -> ingestGraph
                        .invoke(Map.of("text", text, "source", source), RunnableConfig.builder().build())
                        .map(IngestState::ingestedCount)
                        .orElseThrow(() -> new IllegalStateException("Ingest graph produced no final state")));
    }

    /** Hybrid retrieval + optional rerank over already-stored docs. Returns context for generalChat to answer from. */
    public RetrieveResult retrieveContext(String question) {
        return Observation.createNotStarted("rag2.retrieve", observationRegistry)
                .observe(() -> inferenceGraph
                        .invoke(Map.of("question", question), RunnableConfig.builder().build())
                        .map(state -> new RetrieveResult(state.queryEmbedding(), nodes.selectContextDocs(state)))
                        .orElseThrow(() -> new IllegalStateException("Inference graph produced no final state")));
    }
}
