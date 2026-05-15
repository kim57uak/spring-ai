package com.example.springsupervisorai.service.agent.handoff;

import com.example.springsupervisorai.model.DownstreamCallResult;
import com.example.springsupervisorai.model.HandoffPolicyContext;
import com.example.springsupervisorai.model.HandoffValidationResult;
import com.example.springsupervisorai.model.SupervisorPlanningContext;

import java.util.List;

/**
 * downstream handoff 지시를 정책적으로 검증하는 포트.
 * <p>
 * 검증 항목:
 * - feature flag 활성화 여부
 * - 대상 에이전트 허용 목록
 * - 메서드 허용 목록 및 stream 기능
 * - hop 제한 및 반복 경로 차단
 */
public interface HandoffPolicyService {

    /**
     * 호출 배치 결과에서 handoff 지시를 검증한다.
     *
     * @param context handoff 정책 입력 컨텍스트
     * @return 결과별 검증 결과 목록
     */
    List<HandoffValidationResult> evaluate(HandoffPolicyContext context);

    /**
     * 배치 결과에서 handoff 지시를 검증한다 (기본 인자 브릿지).
     *
     * @param planningContext 현재 supervisor planning 컨텍스트
     * @param batchResults 평가할 downstream 결과
     * @return 배치 항목별 검증 결과
     */
    default List<HandoffValidationResult> evaluate(SupervisorPlanningContext context, List<DownstreamCallResult> batchResults) {
        return evaluate(HandoffPolicyContext.of(context, batchResults));
    }
}
