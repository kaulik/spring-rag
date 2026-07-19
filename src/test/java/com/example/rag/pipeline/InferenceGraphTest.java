package com.example.rag.pipeline;

import com.example.rag.common.config.RagProperties;
import com.example.rag.security.InputGuardrailService;
import com.example.rag.common.service.ChunkingService;
import com.example.rag.pipeline.graph.IngestState;
import com.example.rag.pipeline.graph.InferenceState;
import com.example.rag.pipeline.graph.RagGraphFactory;
import com.example.rag.pipeline.graph.RagPipelineNodes;
import com.example.rag.model.ollama.OllamaLlmCalls;
import com.example.rag.vectorstore.DocumentStore;
import com.example.rag.vectorstore.RetrievedDoc;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.RunnableConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the v2 retrieval/ingest graph wiring — no Spring context,
 * all model/store dependencies mocked. The graph is retrieval-only now (no
 * generation); it locks rerank ordering, the skip-path selection done by
 * RagPipelineNodes.selectContextDocs, and rerank failure fallback.
 */
class InferenceGraphTest {

    private static final RetrievedDoc DOC1 = new RetrievedDoc("alpha text", "s1", "s1-chunk-0");
    private static final RetrievedDoc DOC2 = new RetrievedDoc("beta text", "s2", "s2-chunk-0");
    private static final RetrievedDoc DOC3 = new RetrievedDoc("gamma text", "s3", "s3-chunk-0");

    private RagProperties props;
    private ChatModel chatModel;
    private EmbeddingModel embeddingModel;
    private DocumentStore documentStore;
    private InputGuardrailService guardrailService;
    private RagPipelineNodes nodes;
    private RagGraphFactory factory;

    @BeforeEach
    void setUp() {
        props = new RagProperties();
        props.getOllama().setBaseUrl("http://localhost:11434");
        props.getOllama().setEmbeddingModel("embed-model");
        props.getOllama().setChatModel("chat-model");
        props.getOllama().setRerankModel("");
        props.getOllama().setTimeoutSeconds(60);
        props.getRetrieval().setTopK(5);
        props.getRetrieval().setHybridAlpha(0.5);
        props.getRetrieval().setRerankTopK(2);
        props.getRetrieval().setRerankEnabled(true);
        props.getRetrieval().setRerankPreviewChars(150);
        props.getChunking().setSentencesPerChunk(3);
        props.getChunking().setTokenOverlap(8);

        chatModel = mock(ChatModel.class);
        embeddingModel = mock(EmbeddingModel.class);
        documentStore = mock(DocumentStore.class);
        guardrailService = mock(InputGuardrailService.class);

        when(guardrailService.sanitizeRetrievedChunk(any(), any()))
                .thenAnswer(inv -> inv.getArgument(0));
        when(embeddingModel.call(any(EmbeddingRequest.class)))
                .thenReturn(new EmbeddingResponse(List.of(new Embedding(new float[]{0.1f, 0.2f}, 0))));
        when(documentStore.hybridSearch(any(), anyList()))
                .thenReturn(List.of(DOC1, DOC2, DOC3));

        nodes = new RagPipelineNodes(
                props, new ChunkingService(props), documentStore, guardrailService,
                ObservationRegistry.create(),
                new OllamaLlmCalls(chatModel, embeddingModel, new SimpleMeterRegistry()));
        factory = new RagGraphFactory(nodes);
    }

    private static ChatResponse chatResponse(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    /** Mocks the rerank scorer chat call (the only LLM call left in the graph). */
    private void mockRerank(String rerankJson) {
        when(chatModel.call(any(Prompt.class))).thenAnswer(inv -> {
            if (rerankJson == null) throw new RuntimeException("rerank LLM down");
            return chatResponse(rerankJson);
        });
    }

    private InferenceState invokeInference() throws Exception {
        CompiledGraph<InferenceState> graph = factory.buildInferenceGraph();
        Optional<InferenceState> out =
                graph.invoke(Map.of("question", "what is alpha?"), RunnableConfig.builder().build());
        assertTrue(out.isPresent(), "graph must produce a final state");
        return out.get();
    }

    @Test
    void rerankEnabledOrdersByScoreAndTruncates() throws Exception {
        mockRerank("{\"scores\": [{\"id\": 1, \"score\": 4.0}, {\"id\": 2, \"score\": 1.0}, {\"id\": 3, \"score\": 5.0}]}");

        InferenceState state = invokeInference();

        List<RetrievedDoc> sources = nodes.selectContextDocs(state);
        // scores: doc3=5.0, doc1=4.0, doc2=1.0 → top-2 = [doc3, doc1]
        assertEquals(List.of("s3-chunk-0", "s1-chunk-0"),
                sources.stream().map(RetrievedDoc::getChunkId).toList());
    }

    @Test
    void rerankDisabledSelectsHybridOrderTopK() throws Exception {
        props.getRetrieval().setRerankEnabled(false);

        InferenceState state = invokeInference();

        // rerank skipped → no chat call at all; selectContextDocs truncates to top-2 hybrid order
        verify(chatModel, never()).call(any(Prompt.class));
        assertTrue(state.reranked().isEmpty(), "skip path must not set reranked");
        assertEquals(List.of("s1-chunk-0", "s2-chunk-0"),
                nodes.selectContextDocs(state).stream().map(RetrievedDoc::getChunkId).toList());
    }

    @Test
    void rerankFailureFallsBackToHybridOrder() throws Exception {
        mockRerank(null); // rerank branch throws

        InferenceState state = invokeInference();

        assertEquals(List.of("s1-chunk-0", "s2-chunk-0"),
                nodes.selectContextDocs(state).stream().map(RetrievedDoc::getChunkId).toList());
    }

    @Test
    void ingestEmptyTextProducesZeroChunksAndSkipsWeaviate() throws Exception {
        CompiledGraph<IngestState> graph = factory.buildIngestGraph();

        Optional<IngestState> out =
                graph.invoke(Map.of("text", "   ", "source", "test"), RunnableConfig.builder().build());

        assertTrue(out.isPresent());
        assertEquals(0, out.get().ingestedCount());
        verify(documentStore, never()).ingestChunks(anyList(), anyList());
        verify(embeddingModel, never()).call(any(EmbeddingRequest.class));
    }
}
