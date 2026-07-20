package com.mycompany.orchestrator.memory.redis;

import com.mycompany.orchestrator.memory.ConversationMemory;
import com.mycompany.orchestrator.memory.Turn;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Redis adapter for {@link ConversationMemory} — dialogue turns per
 * conversationId, kept separate from per-request TaskStore by design. Redis
 * LIST trimmed to a window, TTL refreshed on write. Fail-open like the Kafka
 * publisher — a Redis outage degrades to memory-less answers, never a failed
 * request.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisConversationMemory implements ConversationMemory {

    private static final Duration TTL = Duration.ofHours(24);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${agents.memory.window:10}")
    private int window;

    private String key(String conversationId) {
        return "conv:" + conversationId;
    }

    @Override
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

    @Override
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

    @Override
    public void replace(String conversationId, List<Turn> turns) {
        try {
            String key = key(conversationId);
            redisTemplate.delete(key);
            if (!turns.isEmpty()) {
                List<String> jsons = new ArrayList<>(turns.size());
                for (Turn turn : turns) {
                    jsons.add(objectMapper.writeValueAsString(turn));
                }
                redisTemplate.opsForList().rightPushAll(key, jsons);
                redisTemplate.expire(key, TTL);
            }
        } catch (Exception e) {
            log.warn("[ConversationMemory] replace failed for {}: {}", conversationId, e.getMessage());
        }
    }
}
