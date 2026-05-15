package com.example.springsupervisorai.model;

import java.util.List;

/**
 * Handoff 정책 평가 입력 모델.
 *
 * @param planningContext 현재 supervisor planning 컨텍스트
 * @param batchResults 최신 invoke 배치의 downstream 결과
 */
public record HandoffPolicyContext(
        SupervisorPlanningContext planningContext,
        List<DownstreamCallResult> batchResults
) {

    /**
     * 널-세이프 handoff 정책 컨텍스트를 생성한다.
     */
    public static HandoffPolicyContext of(
            SupervisorPlanningContext planningContext,
            List<DownstreamCallResult> batchResults
    ) {
        return new HandoffPolicyContext(
                planningContext,
                batchResults == null ? List.of() : List.copyOf(batchResults)
        );
    }
}
