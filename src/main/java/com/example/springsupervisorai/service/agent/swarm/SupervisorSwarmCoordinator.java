package com.example.springsupervisorai.service.agent.swarm;

import com.example.springsupervisorai.model.DownstreamCallResult;
import com.example.springsupervisorai.model.RoutingPlan;
import com.example.springsupervisorai.model.SwarmState;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Supervisor 오케스트레이션에서 SwarmState를 읽고/적용/기록하는 조정 포트.
 * <p>
 * 책임 분리:
 * - 오케스트레이터/그래프는 "언제" 상태를 쓰는지만 결정
 * - 상태 구조/규칙/이력 갱신은 본 코디네이터가 담당
 */
public interface SupervisorSwarmCoordinator {

    /**
     * 세션 기준 최신 스냅샷을 조회한다.
     */
    Optional<SwarmState> loadLatestBySession(String sessionId);

    /**
     * Swarm 공유 facts를 바탕으로 라우팅 계획을 보정한다.
     */
    List<RoutingPlan> applyRoutingRule(String taskId, String sessionId, List<RoutingPlan> planned, Map<String, Object> swarmFacts);

    /**
     * 그래프 노드 이벤트를 상태 로그에 기록한다.
     */
    void recordNodeEvent(String taskId, String sessionId, String nodeType, String message, Map<String, Object> metadata);

    /**
     * downstream 호출 결과를 상태 facts/eventLog에 반영한다.
     */
    void recordInvocationBatch(String taskId, String sessionId, List<DownstreamCallResult> results);
}

