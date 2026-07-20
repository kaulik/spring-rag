package com.mycompany.orchestrator.agent.config;

import com.mycompany.orchestrator.agent.graph.AgentNodes;
import com.mycompany.orchestrator.agent.graph.OrchestratorGraphFactory;
import com.mycompany.orchestrator.agent.graph.OrchestratorState;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Compiles the orchestrator graph once at startup — mirrors RagGraphConfig. */
@Configuration
public class AgentGraphConfig {

    @Bean
    public OrchestratorGraphFactory orchestratorGraphFactory(AgentNodes nodes, BaseCheckpointSaver checkpointSaver) {
        return new OrchestratorGraphFactory(nodes, checkpointSaver);
    }

    @Bean
    public CompiledGraph<OrchestratorState> orchestratorGraph(OrchestratorGraphFactory factory)
            throws GraphStateException {
        return factory.buildOrchestratorGraph();
    }
}
