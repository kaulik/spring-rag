package com.example.rag.v2.config;

import com.example.rag.v2.graph.IngestState;
import com.example.rag.v2.graph.InferenceState;
import com.example.rag.v2.graph.RagV2GraphFactory;
import com.example.rag.v2.graph.RagV2Nodes;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RagV2GraphConfig {

    @Bean
    public RagV2GraphFactory ragV2GraphFactory(RagV2Nodes nodes) {
        return new RagV2GraphFactory(nodes);
    }

    @Bean
    public CompiledGraph<IngestState> ingestGraphV2(RagV2GraphFactory factory) throws GraphStateException {
        return factory.buildIngestGraph();
    }

    @Bean
    public CompiledGraph<InferenceState> inferenceGraphV2(RagV2GraphFactory factory) throws GraphStateException {
        return factory.buildInferenceGraph();
    }
}
