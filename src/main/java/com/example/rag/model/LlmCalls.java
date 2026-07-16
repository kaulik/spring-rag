package com.example.rag.model;

import org.springframework.ai.chat.model.ChatResponse;

import java.util.List;

/**
 * Chat/embedding model port — the swappable seam for LLM calls. Consumers
 * (pipeline, agent, mcp) depend on this interface, not on any provider, so
 * switching Ollama for another chat model (e.g. Anthropic) is a drop-in
 * adapter + a bean swap with no call-site changes. Implementations also
 * record prompt/completion tokens into the ollama.tokens counter so spend
 * stays comparable across every call site.
 *
 * The current adapter is {@link com.example.rag.model.ollama.OllamaLlmCalls}.
 */
public interface LlmCalls {

    String chat(String systemPrompt, String userPrompt, String model, String stage);

    /** temperature=null leaves the provider default; pass 0 for deterministic classification-style calls. */
    String chat(String systemPrompt, String userPrompt, String model, String stage, Double temperature);

    List<Double> embed(String text, String model);

    void recordTokens(String model, String stage, ChatResponse response);
}
