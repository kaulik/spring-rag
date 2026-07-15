package com.example.rag.agent.memory;

import com.example.rag.agent.graph.OrchestratorStateSerializer;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.AbstractCheckpointSaver;
import org.bsc.langgraph4j.checkpoint.Checkpoint;
import org.bsc.langgraph4j.serializer.plain_text.jackson.JacksonCheckpointListSerializer;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.LinkedList;

/**
 * Durable LangGraph4j checkpointing for the orchestrator graph — crash/redeploy
 * resilience (relevant given the blue-green deploy story), not a resume
 * endpoint/UI (none exists yet). Keyed by RunnableConfig.threadId (the
 * conversationId, wired in AgentOrchestratorService), stored as one JSON blob
 * per thread — same conv:{id}/task:{id}-style Redis pattern as
 * ConversationMemoryRepository/TaskStateRepository, just a shorter TTL since
 * this is for recovering an in-flight request, not long-term history.
 * Fail-open like both of those: a Redis outage degrades to no checkpointing,
 * never a failed request — the graph still runs to completion in memory.
 */
@Slf4j
@Repository
public class RedisCheckpointSaver extends AbstractCheckpointSaver {

    private static final Duration TTL = Duration.ofHours(1);

    private final StringRedisTemplate redisTemplate;
    private final JacksonCheckpointListSerializer serializer;

    public RedisCheckpointSaver(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.serializer = new JacksonCheckpointListSerializer(new OrchestratorStateSerializer());
    }

    private String key(String threadId) {
        return "graph:" + threadId;
    }

    @Override
    protected LinkedList<Checkpoint> loadCheckpoints(RunnableConfig config) {
        String threadId = threadId(config);
        try {
            String json = redisTemplate.opsForValue().get(key(threadId));
            return json == null ? new LinkedList<>() : serializer.readDataFromString(json);
        } catch (Exception e) {
            log.warn("[RedisCheckpointSaver] load failed for {}: {}", threadId, e.getMessage());
            return new LinkedList<>();
        }
    }

    @Override
    protected void insertedCheckpoint(RunnableConfig config, LinkedList<Checkpoint> checkpoints, Checkpoint checkpoint) {
        persist(config, checkpoints);
    }

    @Override
    protected void updatedCheckpoint(RunnableConfig config, LinkedList<Checkpoint> checkpoints, Checkpoint checkpoint) {
        persist(config, checkpoints);
    }

    @Override
    protected Tag releaseCheckpoints(RunnableConfig config, LinkedList<Checkpoint> checkpoints) {
        String threadId = threadId(config);
        try {
            redisTemplate.delete(key(threadId));
        } catch (Exception e) {
            log.warn("[RedisCheckpointSaver] release failed for {}: {}", threadId, e.getMessage());
        }
        return new Tag(threadId, checkpoints);
    }

    private void persist(RunnableConfig config, LinkedList<Checkpoint> checkpoints) {
        String threadId = threadId(config);
        try {
            String key = key(threadId);
            redisTemplate.opsForValue().set(key, serializer.writeDataAsString(checkpoints));
            redisTemplate.expire(key, TTL);
        } catch (Exception e) {
            log.warn("[RedisCheckpointSaver] persist failed for {}: {}", threadId, e.getMessage());
        }
    }
}
