package com.example.rag.pipeline.support;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.ollama.api.OllamaEmbeddingOptions;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared Ollama chat/embed entry points for the pipeline, agent, and mcp
 * packages — every call records prompt/completion tokens into the
 * ollama.tokens counter (tags model/stage/direction), keeping token spend
 * comparable across all call sites in the OTLP backend.
 */
@Component
@RequiredArgsConstructor
public class OllamaCalls {

    private final ChatModel ollamaChatModel;
    private final EmbeddingModel ollamaEmbeddingModel;
    private final MeterRegistry meterRegistry;

    public String chat(String systemPrompt, String userPrompt, String model, String stage) {
        Prompt prompt = new Prompt(
                List.of(new SystemMessage(systemPrompt), new UserMessage(userPrompt)),
                OllamaChatOptions.builder().model(model).build());
        ChatResponse response = ollamaChatModel.call(prompt);
        recordTokens(model, stage, response);
        return response.getResult().getOutput().getText();
    }

    public List<Double> embed(String text, String model) {
        EmbeddingRequest request = new EmbeddingRequest(
                List.of(text), OllamaEmbeddingOptions.builder().model(model).build());
        EmbeddingResponse response = ollamaEmbeddingModel.call(request);
        recordTokens(model, "embed", response.getMetadata().getUsage());
        float[] output = response.getResults().get(0).getOutput();
        List<Double> vector = new ArrayList<>(output.length);
        for (float f : output) {
            vector.add((double) f);
        }
        return vector;
    }

    public void recordTokens(String model, String stage, ChatResponse response) {
        if (response.getMetadata() != null) {
            recordTokens(model, stage, response.getMetadata().getUsage());
        }
    }

    public void recordTokens(String model, String stage, Usage usage) {
        if (usage == null) {
            return;
        }
        int promptTokens = usage.getPromptTokens() != null ? usage.getPromptTokens() : 0;
        int completionTokens = usage.getCompletionTokens() != null ? usage.getCompletionTokens() : 0;
        if (promptTokens > 0) {
            meterRegistry.counter("ollama.tokens", "model", model, "stage", stage, "direction", "prompt")
                    .increment(promptTokens);
        }
        if (completionTokens > 0) {
            meterRegistry.counter("ollama.tokens", "model", model, "stage", stage, "direction", "completion")
                    .increment(completionTokens);
        }
    }
}
