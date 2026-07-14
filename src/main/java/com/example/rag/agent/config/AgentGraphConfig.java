package com.example.rag.agent.config;

import com.example.rag.agent.graph.AgentNodes;
import com.example.rag.agent.graph.OrchestratorGraphFactory;
import com.example.rag.agent.graph.OrchestratorState;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Compiles the orchestrator graph once at startup — mirrors RagGraphConfig. */
@Configuration
public class AgentGraphConfig {

    @Bean
    public OrchestratorGraphFactory orchestratorGraphFactory(AgentNodes nodes) {
        return new OrchestratorGraphFactory(nodes);
    }

    @Bean
    public CompiledGraph<OrchestratorState> orchestratorGraph(OrchestratorGraphFactory factory)
            throws GraphStateException {
        return factory.buildOrchestratorGraph();
    }
}
