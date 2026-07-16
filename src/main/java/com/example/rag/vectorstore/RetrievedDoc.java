package com.example.rag.vectorstore;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * A document retrieved from the vector store — the provider-neutral result
 * shape returned by {@link DocumentStore#hybridSearch}. Serializable because
 * it travels inside LangGraph4j pipeline state (InferenceState), which the
 * framework clones via Java serialization on every node transition.
 */
@Data
@AllArgsConstructor
public class RetrievedDoc implements java.io.Serializable {
    private String text;
    private String source;
    private String chunkId;
}
