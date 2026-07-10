package com.example.rag.v2.kafka;

import java.util.List;

/**
 * Published to Kafka for every v2 /api/v2/query request — query, retrieved
 * chunks, the query embedding, and the final answer, in one message.
 * Chunk-level embeddings are not included: WeaviateService discards them
 * after retrieval, so only the query embedding exists in-process.
 */
public record RagEvent(
        String requestId,
        long timestamp,
        String question,
        List<Double> queryEmbedding,
        List<ChunkRef> retrievedChunks,
        String answer) {

    public record ChunkRef(String text, String source, String chunkId) {}
}
