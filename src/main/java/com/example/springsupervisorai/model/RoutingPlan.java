package com.example.springsupervisorai.model;

import java.util.Map;

/**
 * Supervisor가 실행할 단일 라우팅 계획 값 객체.
 *
 * @param agentKey 호출 대상 하위 에이전트 키
 * @param method A2A JSON-RPC 메서드
 * @param reason 선택 사유(로그/트레이스 용도)
 * @param priority 우선순위(낮을수록 먼저 실행)
 * @param arguments 하위 에이전트 호출 인자
 * @param sourceType 계획 출처(`PLANNER` 또는 `HANDOFF`)
 * @param handoffDepth handoff 체인 깊이(0이면 planner 생성 계획)
 * @param parentAgentKey handoff를 유발한 상위 에이전트 키
 */
public record RoutingPlan(
        String agentKey,
        String method,
        String reason,
        int priority,
        Map<String, Object> arguments,
        String sourceType,
        int handoffDepth,
        String parentAgentKey
) {

    /**
     * 기존 planner 경로 호환을 위한 축약 생성자.
     * sourceType은 `PLANNER`, handoffDepth는 0으로 고정한다.
     */
    public RoutingPlan(
            String agentKey,
            String method,
            String reason,
            int priority,
            Map<String, Object> arguments
    ) {
        this(agentKey, method, reason, priority, arguments, "PLANNER", 0, "");
    }

    /**
     * handoff에서 생성된 계획인지 반환한다.
     *
     * @return sourceType이 `HANDOFF`면 true
     */
    public boolean isHandoff() {
        return "HANDOFF".equalsIgnoreCase(sourceType == null ? "" : sourceType);
    }
}
