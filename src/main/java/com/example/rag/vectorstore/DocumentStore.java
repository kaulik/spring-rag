package com.example.rag.vectorstore;

import com.example.rag.common.service.ChunkingService.Chunk;

import java.util.List;

/**
 * Vector-store port — the swappable seam for document ingestion and retrieval.
 * Consumers (the RAG pipeline, MCP tools) depend on this interface, not on any
 * concrete store, so switching Weaviate for another vector database is a
 * drop-in adapter + a bean swap with no call-site changes.
 *
 * The current adapter is {@link com.example.rag.vectorstore.weaviate.WeaviateDocumentStore}.
 */
public interface DocumentStore {

    /** Batch-insert chunks together with their pre-computed embedding vectors. */
    void ingestChunks(List<Chunk> chunks, List<List<Double>> embeddings);

    /** Hybrid (keyword + vector) search; returns up to the configured top-k documents. */
    List<RetrievedDoc> hybridSearch(String query, List<Double> queryEmbedding);
}
