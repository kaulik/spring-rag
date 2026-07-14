package com.example.rag.agent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Conversational memory: dialogue turns per conversationId, kept separate
 * from per-request TaskState by design. Redis LIST trimmed to a window,
 * TTL refreshed on write. Fail-open like the Kafka publisher — a Redis
 * outage degrades to memory-less answers, never a failed request.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class ConversationMemoryRepository {

    private static final Duration TTL = Duration.ofHours(24);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${agents.memory.window:10}")
    private int window;

    private String key(String conversationId) {
        return "conv:" + conversationId;
    }

    public void append(String conversationId, Turn turn) {
        try {
            String json = objectMapper.writeValueAsString(turn);
            String key = key(conversationId);
            redisTemplate.opsForList().rightPush(key, json);
            redisTemplate.opsForList().trim(key, -window, -1);
            redisTemplate.expire(key, TTL);
        } catch (Exception e) {
            log.warn("[ConversationMemory] append failed for {}: {}", conversationId, e.getMessage());
        }
    }

    public List<Turn> recentTurns(String conversationId) {
        try {
            List<String> raw = redisTemplate.opsForList().range(key(conversationId), 0, -1);
            if (raw == null) {
                return List.of();
            }
            List<Turn> turns = new ArrayList<>(raw.size());
            for (String json : raw) {
                turns.add(objectMapper.readValue(json, Turn.class));
            }
            return turns;
        } catch (Exception e) {
            log.warn("[ConversationMemory] read failed for {}: {}", conversationId, e.getMessage());
            return List.of();
        }
    }
}
