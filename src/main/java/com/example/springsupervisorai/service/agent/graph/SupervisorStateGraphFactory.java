package com.example.springsupervisorai.service.agent.graph;

import com.example.springsupervisorai.model.SupervisorGraphState;
import org.bsc.langgraph4j.CompiledGraph;

/**
 * Supervisor 상태 그래프 팩토리 포트.
 * <p>
 * 오케스트레이터가 실행할 컴파일된 LangGraph 인스턴스를 제공한다.
 */
public interface SupervisorStateGraphFactory {

    /**
     * 컴파일된 Supervisor 그래프를 반환한다.
     *
     * @return compiled graph 인스턴스
     */
    CompiledGraph<SupervisorGraphState> getCompiledGraph();
}
