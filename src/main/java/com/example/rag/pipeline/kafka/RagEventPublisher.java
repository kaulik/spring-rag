package com.example.rag.pipeline.kafka;

import com.example.rag.pipeline.graph.InferenceState;
import com.example.rag.vectorstore.RetrievedDoc;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Publishes one RagEvent per v2 inference request to Kafka. Fire-and-forget:
 * KafkaTemplate.send() never blocks on the caller's thread, and every
 * failure (build or send) is swallowed and logged — a Kafka outage or
 * serialization bug must never affect the /api/v2/query response.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagEventPublisher {

    private static final String TOPIC = "rag-events";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public void publish(InferenceState state) {
        try {
            String requestId = UUID.randomUUID().toString();
            List<RetrievedDoc> docs = state.reranked().orElse(state.sanitized());

            RagEvent event = new RagEvent(
                    requestId,
                    Instant.now().toEpochMilli(),
                    state.question(),
                    state.queryEmbedding(),
                    docs.stream()
                            .map(d -> new RagEvent.ChunkRef(d.getText(), d.getSource(), d.getChunkId()))
                            .collect(Collectors.toList()),
                    state.answer());

            String json = objectMapper.writeValueAsString(event);
            kafkaTemplate.send(TOPIC, requestId, json).whenComplete((result, ex) -> {
                if (ex != null) {
                    log.warn("[RagEventPublisher] failed to publish event {} to '{}': {}",
                            requestId, TOPIC, ex.getMessage());
                }
            });
        } catch (Exception e) {
            log.warn("[RagEventPublisher] failed to build/publish RAG event: {}", e.getMessage());
        }
    }
}
