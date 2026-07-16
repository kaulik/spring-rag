package com.example.rag.mcp;

/**
 * MCP-facing chunk shape. A dedicated record (not vectorstore.RetrievedDoc
 * directly) so tool-argument deserialization has a guaranteed Jackson
 * record constructor rather than relying on Lombok @Data's generated
 * no-arg-constructor-plus-setters, which Jackson doesn't reliably bind to
 * without extra annotations.
 */
public record ChunkDto(String text, String source, String chunkId) {}
