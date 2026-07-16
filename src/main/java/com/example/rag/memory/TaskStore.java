package com.example.rag.memory;

/**
 * Task-state port — the swappable seam for the per-request execution record
 * (status, intent, agent, tool-call count) kept for audit/debugging with a
 * shorter lifecycle than conversation memory. Consumers depend on this
 * interface, not on any store. Implementations are fail-open: a store outage
 * never fails the request.
 *
 * The current adapter is {@link com.example.rag.memory.redis.RedisTaskStore}.
 */
public interface TaskStore {

    void start(String requestId, String conversationId);

    void running(String requestId, String intent, String agent);

    void done(String requestId);

    void failed(String requestId, String error);

    void incrementToolCalls(String requestId);
}
