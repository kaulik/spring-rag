package com.example.rag.v2;

import com.example.rag.config.RagProperties;
import com.example.rag.security.InputGuardrailService;
import com.example.rag.service.ChunkingService;
import com.example.rag.service.ResponseSanitizer;
import com.example.rag.v2.graph.IngestState;
import com.example.rag.v2.graph.InferenceState;
import com.example.rag.v2.graph.RagV2GraphFactory;
import com.example.rag.v2.graph.RagV2Nodes;
import com.example.rag.weaviate.WeaviateService;
import com.example.rag.weaviate.WeaviateService.RetrievedDoc;
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
 * Unit tests for the v2 inference/ingest graph wiring — no Spring context,
 * all model/store dependencies mocked. Locks the behavioral parity contract
 * with v1 (rerank ordering, disabled-path truncation, failure fallback).
 */
class InferenceGraphTest {

    private static final RetrievedDoc DOC1 = new RetrievedDoc("alpha text", "s1", "s1-chunk-0");
    private static final RetrievedDoc DOC2 = new RetrievedDoc("beta text", "s2", "s2-chunk-0");
    private static final RetrievedDoc DOC3 = new RetrievedDoc("gamma text", "s3", "s3-chunk-0");

    private RagProperties props;
    private ChatModel chatModel;
    private EmbeddingModel embeddingModel;
    private WeaviateService weaviateService;
    private InputGuardrailService guardrailService;
    private ResponseSanitizer responseSanitizer;
    private RagV2GraphFactory factory;

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
        weaviateService = mock(WeaviateService.class);
        guardrailService = mock(InputGuardrailService.class);
        responseSanitizer = mock(ResponseSanitizer.class);

        when(guardrailService.sanitizeRetrievedChunk(any(), any()))
                .thenAnswer(inv -> inv.getArgument(0));
        when(responseSanitizer.sanitize(any())).thenAnswer(inv -> inv.getArgument(0));
        when(embeddingModel.call(any(EmbeddingRequest.class)))
                .thenReturn(new EmbeddingResponse(List.of(new Embedding(new float[]{0.1f, 0.2f}, 0))));
        when(weaviateService.hybridSearch(any(), anyList()))
                .thenReturn(List.of(DOC1, DOC2, DOC3));

        RagV2Nodes nodes = new RagV2Nodes(
                props, new ChunkingService(props), weaviateService, guardrailService,
                responseSanitizer, ObservationRegistry.create(), new SimpleMeterRegistry(),
                chatModel, embeddingModel);
        factory = new RagV2GraphFactory(nodes);
    }

    private static ChatResponse chatResponse(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    /** Routes mocked chat calls by system prompt: rerank scorer vs answer generator. */
    private void mockChat(String rerankJson, String answer) {
        when(chatModel.call(any(Prompt.class))).thenAnswer(inv -> {
            Prompt p = inv.getArgument(0);
            String system = p.getInstructions().get(0).getText();
            if (system.contains("document relevance scorer")) {
                if (rerankJson == null) throw new RuntimeException("rerank LLM down");
                return chatResponse(rerankJson);
            }
            return chatResponse(answer);
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
        mockChat("{\"scores\": [{\"id\": 1, \"score\": 4.0}, {\"id\": 2, \"score\": 1.0}, {\"id\": 3, \"score\": 5.0}]}",
                "the answer");

        InferenceState state = invokeInference();

        assertEquals("the answer", state.answer());
        List<RetrievedDoc> sources = state.reranked().orElseThrow();
        // scores: doc3=5.0, doc1=4.0, doc2=1.0 → top-2 = [doc3, doc1]
        assertEquals(List.of("s3-chunk-0", "s1-chunk-0"),
                sources.stream().map(RetrievedDoc::getChunkId).toList());
    }

    @Test
    void rerankDisabledKeepsHybridOrderTopK() throws Exception {
        props.getRetrieval().setRerankEnabled(false);
        mockChat(null, "the answer");

        InferenceState state = invokeInference();

        // top-2 in hybrid order, no rerank chat call made
        assertEquals(List.of("s1-chunk-0", "s2-chunk-0"),
                state.reranked().orElseThrow().stream().map(RetrievedDoc::getChunkId).toList());
        verify(chatModel, times(1)).call(any(Prompt.class)); // generate only
    }

    @Test
    void rerankFailureFallsBackToHybridOrder() throws Exception {
        mockChat(null, "the answer"); // rerank branch throws, generate succeeds

        InferenceState state = invokeInference();

        assertEquals("the answer", state.answer());
        assertEquals(List.of("s1-chunk-0", "s2-chunk-0"),
                state.reranked().orElseThrow().stream().map(RetrievedDoc::getChunkId).toList());
    }

    @Test
    void ingestEmptyTextProducesZeroChunksAndSkipsWeaviate() throws Exception {
        CompiledGraph<IngestState> graph = factory.buildIngestGraph();

        Optional<IngestState> out =
                graph.invoke(Map.of("text", "   ", "source", "test"), RunnableConfig.builder().build());

        assertTrue(out.isPresent());
        assertEquals(0, out.get().ingestedCount());
        verify(weaviateService, never()).ingestChunks(anyList(), anyList());
        verify(embeddingModel, never()).call(any(EmbeddingRequest.class));
    }
}
