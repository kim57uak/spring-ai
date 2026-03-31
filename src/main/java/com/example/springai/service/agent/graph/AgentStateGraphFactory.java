package com.example.springai.service.agent.graph;

import com.example.springai.model.agent.AgentGraphState;
import org.bsc.langgraph4j.CompiledGraph;

/**
 * Agent 상태 그래프 컴파일 결과를 제공하는 팩토리 계약.
 */
public interface AgentStateGraphFactory {

    /**
     * 재사용 가능한 컴파일된 그래프를 반환한다.
     */
    CompiledGraph<AgentGraphState> getCompiledGraph();
}
