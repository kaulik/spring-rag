package com.example.rag.agent.graph;

import org.bsc.langgraph4j.serializer.plain_text.jackson.JacksonStateSerializer;

/** Enables Jackson (de)serialization of OrchestratorState, needed for LangGraph4j checkpointing. */
public class OrchestratorStateSerializer extends JacksonStateSerializer<OrchestratorState> {

    public OrchestratorStateSerializer() {
        super(OrchestratorState::new);
    }
}
