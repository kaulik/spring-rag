package com.example.rag.v2.service;

import com.example.rag.v2.graph.IngestState;
import com.example.rag.v2.graph.InferenceState;
import com.example.rag.v2.kafka.RagEventPublisher;
import com.example.rag.weaviate.WeaviateService.RetrievedDoc;
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
 * Facade over the compiled v2 graphs — the LangGraph4j counterpart of
 * RagService. Same inputs/outputs as v1 so /api/v2 responses stay
 * contract-identical to /api.
 */
@Slf4j
@Service
public class RagV2Service {

    private final CompiledGraph<IngestState> ingestGraph;
    private final CompiledGraph<InferenceState> inferenceGraph;
    private final ObservationRegistry observationRegistry;
    private final RagEventPublisher ragEventPublisher;

    public RagV2Service(@Qualifier("ingestGraphV2") CompiledGraph<IngestState> ingestGraph,
                        @Qualifier("inferenceGraphV2") CompiledGraph<InferenceState> inferenceGraph,
                        ObservationRegistry observationRegistry,
                        RagEventPublisher ragEventPublisher) {
        this.ingestGraph = ingestGraph;
        this.inferenceGraph = inferenceGraph;
        this.observationRegistry = observationRegistry;
        this.ragEventPublisher = ragEventPublisher;
    }

    public record RagV2Result(String answer, List<RetrievedDoc> sources) {}

    /** Chunk, embed, and store raw text. @return number of chunks ingested. */
    public int ingestContextText(String text, String source) {
        return Observation.createNotStarted("rag2.ingest", observationRegistry)
                .lowCardinalityKeyValue("source", source)
                .observe(() -> ingestGraph
                        .invoke(Map.of("text", text, "source", source), RunnableConfig.builder().build())
                        .map(IngestState::ingestedCount)
                        .orElseThrow(() -> new IllegalStateException("Ingest graph produced no final state")));
    }

    /** Hybrid RAG + rerank over already-stored docs via the inference graph. */
    public RagV2Result answerQuestion(String question) {
        return Observation.createNotStarted("rag2.answer", observationRegistry)
                .observe(() -> inferenceGraph
                        .invoke(Map.of("question", question), RunnableConfig.builder().build())
                        .map(state -> {
                            ragEventPublisher.publish(state);
                            return new RagV2Result(state.answer(), state.reranked().orElse(List.of()));
                        })
                        .orElseThrow(() -> new IllegalStateException("Inference graph produced no final state")));
    }
}
