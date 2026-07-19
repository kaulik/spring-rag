package com.example.rag.pipeline.kafka;

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
 * Publishes one RagEvent per knowledge-base answer to Kafka. Fire-and-forget:
 * KafkaTemplate.send() never blocks on the caller's thread, and every failure
 * (build or send) is swallowed and logged — a Kafka outage or serialization
 * bug must never affect the /api/v2/agent response. Called from the
 * orchestrator's generalChat node once it has synthesized the final answer
 * from retrieved context (decoupled from any pipeline graph state, hence the
 * primitive signature).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagEventPublisher {

    private static final String TOPIC = "rag-events";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public void publish(String question, List<Double> queryEmbedding, List<RetrievedDoc> chunks, String answer) {
        try {
            String requestId = UUID.randomUUID().toString();

            RagEvent event = new RagEvent(
                    requestId,
                    Instant.now().toEpochMilli(),
                    question,
                    queryEmbedding,
                    chunks.stream()
                            .map(d -> new RagEvent.ChunkRef(d.getText(), d.getSource(), d.getChunkId()))
                            .collect(Collectors.toList()),
                    answer);

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
