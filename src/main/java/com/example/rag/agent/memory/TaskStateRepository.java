package com.example.rag.agent.memory;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Per-request task execution state — audit/debugging record with a
 * deliberately different lifecycle (1h TTL) than conversational memory.
 * Fail-open: Redis being down never fails the request.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class TaskStateRepository {

    public enum Status { ROUTING, RUNNING, DONE, FAILED }

    private static final Duration TTL = Duration.ofHours(1);

    private final StringRedisTemplate redisTemplate;

    private String key(String requestId) {
        return "task:" + requestId;
    }

    public void start(String requestId, String conversationId) {
        put(requestId, Map.of(
                "status", Status.ROUTING.name(),
                "conversationId", conversationId,
                "startedAt", Instant.now().toString(),
                "toolCalls", "0"));
    }

    public void running(String requestId, String intent, String agent) {
        put(requestId, Map.of(
                "status", Status.RUNNING.name(),
                "intent", intent,
                "agent", agent));
    }

    public void done(String requestId) {
        put(requestId, Map.of(
                "status", Status.DONE.name(),
                "finishedAt", Instant.now().toString()));
    }

    public void failed(String requestId, String error) {
        put(requestId, Map.of(
                "status", Status.FAILED.name(),
                "finishedAt", Instant.now().toString(),
                "error", error == null ? "" : error));
    }

    public void incrementToolCalls(String requestId) {
        try {
            redisTemplate.opsForHash().increment(key(requestId), "toolCalls", 1);
        } catch (Exception e) {
            log.warn("[TaskState] toolCalls increment failed for {}: {}", requestId, e.getMessage());
        }
    }

    private void put(String requestId, Map<String, String> fields) {
        try {
            String key = key(requestId);
            redisTemplate.opsForHash().putAll(key, fields);
            redisTemplate.expire(key, TTL);
        } catch (Exception e) {
            log.warn("[TaskState] write failed for {}: {}", requestId, e.getMessage());
        }
    }
}
