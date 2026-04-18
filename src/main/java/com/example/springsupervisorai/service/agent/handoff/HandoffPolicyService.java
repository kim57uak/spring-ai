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
 * - target agent allowlist
 * - method allowlist 및 stream capability
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
     * 호출 배치 결과에서 handoff 지시를 검증한다.
     *
     * @param context 현재 supervisor 컨텍스트
     * @param batchResults 직전 invoke 배치 결과
     * @return 결과별 검증 결과 목록
     */
    default List<HandoffValidationResult> evaluate(SupervisorPlanningContext context, List<DownstreamCallResult> batchResults) {
        return evaluate(HandoffPolicyContext.of(context, batchResults));
    }
}
