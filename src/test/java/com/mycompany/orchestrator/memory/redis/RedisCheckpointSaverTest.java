package com.mycompany.orchestrator.memory.redis;

import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.Checkpoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * RedisCheckpointSaver round-trips a Checkpoint through a JSON blob per
 * threadId — mirrors RedisConversationMemory's/RedisTaskStore's
 * mocked-StringRedisTemplate test style. Fail-open is the key contract: a
 * Redis outage must never surface as an exception from put()/list(), since
 * checkpointing is best-effort resilience, not on the primary request path.
 */
class RedisCheckpointSaverTest {

    private StringRedisTemplate redisTemplate;
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);
    private RedisCheckpointSaver saver;
    private RunnableConfig config;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        saver = new RedisCheckpointSaver(redisTemplate);
        config = RunnableConfig.builder().threadId("conv-1").build();
    }

    private static Checkpoint checkpoint(String id) {
        return Checkpoint.builder()
                .id(id)
                .state(Map.of("answer", "hi"))
                .nodeId("route")
                .nextNodeId("stockAgent")
                .build();
    }

    @Test
    void putWritesJsonAndSetsTtl() throws Exception {
        when(valueOps.get(anyString())).thenReturn(null);

        saver.put(config, checkpoint("cp-1"));

        verify(valueOps).set(eq("graph:conv-1"), anyString());
        verify(redisTemplate).expire(eq("graph:conv-1"), eq(Duration.ofHours(1)));
    }

    @Test
    void putThenGetRoundTripsTheCheckpoint() throws Exception {
        AtomicReference<String> stored = new AtomicReference<>();
        when(valueOps.get("graph:conv-1")).thenAnswer(inv -> stored.get());
        doAnswer(inv -> {
            stored.set(inv.getArgument(1));
            return null;
        }).when(valueOps).set(eq("graph:conv-1"), anyString());

        saver.put(config, checkpoint("cp-1"));
        Optional<Checkpoint> loaded = saver.get(config);

        assertTrue(loaded.isPresent());
        assertEquals("cp-1", loaded.get().getId());
        assertEquals("route", loaded.get().getNodeId());
        assertEquals("hi", loaded.get().getState().get("answer"));
    }

    @Test
    void redisOutageIsSwallowedNotPropagated() {
        when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("redis down"));

        assertDoesNotThrow(() -> saver.put(config, checkpoint("cp-1")));
        Collection<Checkpoint> list = saver.list(config);
        assertTrue(list.isEmpty());
    }
}
