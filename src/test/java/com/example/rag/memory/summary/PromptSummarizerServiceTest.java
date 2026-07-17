package com.example.rag.memory.summary;

import com.example.rag.common.config.RagProperties;
import com.example.rag.memory.ConversationMemory;
import com.example.rag.memory.Turn;
import com.example.rag.model.ollama.OllamaLlmCalls;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Exercises PromptSummarizerService's trigger threshold and its two
 * fail-open paths (LLM failure must never fail the request; a
 * below-threshold history must never touch the model or Redis at all).
 */
class PromptSummarizerServiceTest {

    private ChatModel chatModel;
    private ConversationMemory conversationMemory;
    private SummarizerProperties summarizerProperties;
    private PromptSummarizerService service;

    @BeforeEach
    void setUp() {
        RagProperties ragProps = new RagProperties();
        ragProps.getOllama().setChatModel("chat-model");

        summarizerProperties = new SummarizerProperties();
        summarizerProperties.setTriggerChars(50);

        chatModel = mock(ChatModel.class);
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        conversationMemory = mock(ConversationMemory.class);
        OllamaLlmCalls ollamaCalls = new OllamaLlmCalls(chatModel, embeddingModel, new SimpleMeterRegistry());

        service = new PromptSummarizerService(ragProps, summarizerProperties, ollamaCalls, conversationMemory,
                ObservationRegistry.create(), new SimpleMeterRegistry());
    }

    private static ChatResponse chatResponse(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private static Turn turn(String role, String text) {
        return new Turn(role, text, 1L);
    }

    @Test
    void belowThresholdHistoryIsReturnedUnchangedWithoutTouchingModelOrStore() {
        List<Turn> turns = List.of(turn(Turn.ROLE_USER, "hi"), turn(Turn.ROLE_ASSISTANT, "hello"));

        List<Turn> result = service.maybeSummarize("conv-1", turns);

        assertSame(turns, result);
        verifyNoInteractions(chatModel);
        verify(conversationMemory, never()).replace(any(), any());
    }

    @Test
    void aboveThresholdHistoryIsCondensedIntoOneSummaryTurnAndPersisted() {
        String longText = "x".repeat(60);
        List<Turn> turns = List.of(turn(Turn.ROLE_USER, longText));
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse("condensed summary"));

        List<Turn> result = service.maybeSummarize("conv-2", turns);

        assertEquals(1, result.size());
        assertEquals(Turn.ROLE_SUMMARY, result.get(0).role());
        assertEquals("condensed summary", result.get(0).text());
        verify(conversationMemory).replace(eq("conv-2"), eq(result));
    }

    @Test
    void llmFailureAboveThresholdFallsBackToOriginalTurnsWithoutPersisting() {
        String longText = "x".repeat(60);
        List<Turn> turns = List.of(turn(Turn.ROLE_USER, longText));
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("ollama down"));

        List<Turn> result = service.maybeSummarize("conv-3", turns);

        assertSame(turns, result);
        verify(conversationMemory, never()).replace(any(), any());
    }
}
