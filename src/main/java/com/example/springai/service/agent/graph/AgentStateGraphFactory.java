package com.example.springai.service.agent.graph;

import com.example.springai.model.agent.AgentGraphState;
import org.bsc.langgraph4j.CompiledGraph;

public interface AgentStateGraphFactory {
    CompiledGraph<AgentGraphState> getCompiledGraph();
}
