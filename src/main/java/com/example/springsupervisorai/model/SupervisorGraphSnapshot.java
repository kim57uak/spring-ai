package com.example.springsupervisorai.model;

import java.util.List;
import java.util.Map;

/**
 * Supervisor graph 상태의 typed snapshot.
 *
 * @param taskId task id
 * @param sessionId 세션 id
 * @param userMessage 사용자 메시지
 * @param model 모델 식별자
 * @param history 대화 히스토리
 * @param checkpointId checkpoint payload
 * @param currentNode 현재 graph node
 * @param routingPlans 라우팅 계획 목록
 * @param routingIndex 현재 라우팅 인덱스
 * @param results 누적 downstream 결과
 * @param lastInvokeBatchResults 직전 invoke 배치 결과
 * @param handoffValidations handoff 검증 결과
 * @param handoffEnabled handoff 활성화 여부
 * @param swarmSharedFacts swarm 공유 팩트
 * @param swarmStateVersion swarm 상태 버전
 */
public record SupervisorGraphSnapshot(
        String taskId,
        String sessionId,
        String userMessage,
        String model,
        List<String> history,
        String checkpointId,
        String currentNode,
        List<RoutingPlan> routingPlans,
        int routingIndex,
        List<DownstreamCallResult> results,
        List<DownstreamCallResult> lastInvokeBatchResults,
        List<HandoffValidationResult> handoffValidations,
        boolean handoffEnabled,
        Map<String, Object> swarmSharedFacts,
        long swarmStateVersion
) {
}
