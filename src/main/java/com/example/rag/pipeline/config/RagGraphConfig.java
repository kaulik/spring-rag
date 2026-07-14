package com.example.rag.pipeline.config;

import com.example.rag.pipeline.graph.IngestState;
import com.example.rag.pipeline.graph.InferenceState;
import com.example.rag.pipeline.graph.RagGraphFactory;
import com.example.rag.pipeline.graph.RagPipelineNodes;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RagGraphConfig {

    @Bean
    public RagGraphFactory ragGraphFactory(RagPipelineNodes nodes) {
        return new RagGraphFactory(nodes);
    }

    @Bean
    public CompiledGraph<IngestState> ingestGraph(RagGraphFactory factory) throws GraphStateException {
        return factory.buildIngestGraph();
    }

    @Bean
    public CompiledGraph<InferenceState> inferenceGraph(RagGraphFactory factory) throws GraphStateException {
        return factory.buildInferenceGraph();
    }
}
