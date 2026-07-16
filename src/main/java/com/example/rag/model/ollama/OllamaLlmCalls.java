package com.example.rag.model.ollama;

import com.example.rag.model.LlmCalls;
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
 * Ollama adapter for {@link LlmCalls} — shared chat/embed entry points for the
 * pipeline, agent, and mcp packages. Every call records prompt/completion
 * tokens into the ollama.tokens counter (tags model/stage/direction), keeping
 * token spend comparable across all call sites in the OTLP backend.
 */
@Component
@RequiredArgsConstructor
public class OllamaLlmCalls implements LlmCalls {

    private final ChatModel ollamaChatModel;
    private final EmbeddingModel ollamaEmbeddingModel;
    private final MeterRegistry meterRegistry;

    @Override
    public String chat(String systemPrompt, String userPrompt, String model, String stage) {
        return chat(systemPrompt, userPrompt, model, stage, null);
    }

    /**
     * temperature=null leaves Ollama's own default (high, ~0.8) in place —
     * fine for conversational replies. Classification-style calls (e.g. the
     * orchestrator's intent router) should pass a low/zero temperature: a
     * single-word decision needs to be deterministic, not creative — the
     * same question landing on a different intent from run to run is a bug,
     * not a feature.
     */
    @Override
    public String chat(String systemPrompt, String userPrompt, String model, String stage, Double temperature) {
        OllamaChatOptions.Builder options = OllamaChatOptions.builder().model(model);
        if (temperature != null) {
            options.temperature(temperature);
        }
        Prompt prompt = new Prompt(
                List.of(new SystemMessage(systemPrompt), new UserMessage(userPrompt)),
                options.build());
        ChatResponse response = ollamaChatModel.call(prompt);
        recordTokens(model, stage, response);
        return response.getResult().getOutput().getText();
    }

    @Override
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

    @Override
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
