package com.example.springsupervisorai.service.agent.invoke;

import com.example.springsupervisorai.model.DownstreamCallResult;
import com.example.springsupervisorai.model.InvocationPolicyContext;
import com.example.springsupervisorai.model.RoutingPlan;
import com.example.springsupervisorai.model.SupervisorPlanningContext;

/**
 * Supervisor downstream A2A 호출 포트.
 * <p>
 * 단일 RoutingPlan 단위 호출을 수행하고 표준 결과를 반환한다.
 */
public interface A2AInvocationService {

    /**
     * 단일 invocation 컨텍스트를 실행한다.
     *
     * @param context 실행 컨텍스트
     * @return 표준화된 downstream 호출 결과
     */
    DownstreamCallResult invoke(InvocationPolicyContext context);

    /**
     * 단일 라우팅 계획을 실행한다.
     *
     * @param plan 실행할 라우팅 계획
     * @param context 실행 컨텍스트
     * @return 표준화된 downstream 호출 결과
     */
    default DownstreamCallResult invoke(RoutingPlan plan, SupervisorPlanningContext context) {
        return invoke(InvocationPolicyContext.of(plan, context));
    }

    /**
     * 특정 세션의 모든 downstream 호출을 취소한다.
     * <p>
     * 각 downstream agent에 대해 CancelTask JSON-RPC를 전송한다.
     *
     * @param sessionId 취소 대상 세션 id
     */
    void cancelDownstream(String sessionId);

    /**
     * 특정 세션의 모든 downstream 세션을 정리한다.
     * <p>
     * 각 downstream agent에 대해 세션 clear 요청을 전송한다.
     *
     * @param sessionId 정리 대상 세션 id
     */
    void clearDownstream(String sessionId);
}
