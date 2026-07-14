package com.example.rag.pipeline.support;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Shared Ollama chat entry points for the pipeline and agent packages —
 * every call records prompt/completion tokens into the ollama.tokens
 * counter (tags model/stage/direction), keeping token spend comparable
 * across all call sites in the OTLP backend.
 */
@Component
@RequiredArgsConstructor
public class OllamaCalls {

    private final ChatModel ollamaChatModel;
    private final MeterRegistry meterRegistry;

    public String chat(String systemPrompt, String userPrompt, String model, String stage) {
        Prompt prompt = new Prompt(
                List.of(new SystemMessage(systemPrompt), new UserMessage(userPrompt)),
                OllamaChatOptions.builder().model(model).build());
        ChatResponse response = ollamaChatModel.call(prompt);
        recordTokens(model, stage, response);
        return response.getResult().getOutput().getText();
    }

    /**
     * Spring AI-driven cyclic agent loop: with internal tool execution
     * enabled, Spring AI's ToolCallingManager executes requested tools and
     * re-prompts until the model returns a final text answer. toolContext
     * is passed through to @Tool methods declaring a ToolContext parameter.
     */
    public String chatWithTools(String systemPrompt, String userPrompt, String model, String stage,
                                List<ToolCallback> tools, Map<String, Object> toolContext) {
        Prompt prompt = new Prompt(
                List.of(new SystemMessage(systemPrompt), new UserMessage(userPrompt)),
                OllamaChatOptions.builder()
                        .model(model)
                        .toolCallbacks(tools)
                        .internalToolExecutionEnabled(true)
                        .toolContext(toolContext)
                        .build());
        ChatResponse response = ollamaChatModel.call(prompt);
        recordTokens(model, stage, response);
        return response.getResult().getOutput().getText();
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
