package com.example.rag.pipeline.service;

import com.example.rag.pipeline.graph.IngestState;
import com.example.rag.pipeline.graph.InferenceState;
import com.example.rag.pipeline.kafka.RagEventPublisher;
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

/** Facade over the compiled pipeline graphs (LangGraph4j ingest + inference). */
@Slf4j
@Service
public class RagPipelineService {

    private final CompiledGraph<IngestState> ingestGraph;
    private final CompiledGraph<InferenceState> inferenceGraph;
    private final ObservationRegistry observationRegistry;
    private final RagEventPublisher ragEventPublisher;

    public RagPipelineService(@Qualifier("ingestGraph") CompiledGraph<IngestState> ingestGraph,
                        @Qualifier("inferenceGraph") CompiledGraph<InferenceState> inferenceGraph,
                        ObservationRegistry observationRegistry,
                        RagEventPublisher ragEventPublisher) {
        this.ingestGraph = ingestGraph;
        this.inferenceGraph = inferenceGraph;
        this.observationRegistry = observationRegistry;
        this.ragEventPublisher = ragEventPublisher;
    }

    public record RagPipelineResult(String answer, List<RetrievedDoc> sources) {}

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
    public RagPipelineResult answerQuestion(String question) {
        return Observation.createNotStarted("rag2.answer", observationRegistry)
                .observe(() -> inferenceGraph
                        .invoke(Map.of("question", question), RunnableConfig.builder().build())
                        .map(state -> {
                            ragEventPublisher.publish(state);
                            return new RagPipelineResult(state.answer(), state.reranked().orElse(List.of()));
                        })
                        .orElseThrow(() -> new IllegalStateException("Inference graph produced no final state")));
    }
}
