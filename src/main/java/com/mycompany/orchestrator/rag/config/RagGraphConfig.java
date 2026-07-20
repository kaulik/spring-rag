package com.mycompany.orchestrator.rag.config;

import com.mycompany.orchestrator.rag.graph.InferenceState;
import com.mycompany.orchestrator.rag.graph.IngestState;
import com.mycompany.orchestrator.rag.graph.RagGraphFactory;
import com.mycompany.orchestrator.rag.graph.RagPipelineNodes;

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
