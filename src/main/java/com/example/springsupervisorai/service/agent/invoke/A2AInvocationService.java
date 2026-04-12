package com.example.springsupervisorai.service.agent.invoke;

import com.example.springsupervisorai.model.DownstreamCallResult;
import com.example.springsupervisorai.model.RoutingPlan;
import com.example.springsupervisorai.model.SupervisorPlanningContext;

/**
 * Supervisor downstream A2A 호출 포트.
 * <p>
 * 단일 RoutingPlan 단위 호출을 수행하고 표준 결과를 반환한다.
 */
public interface A2AInvocationService {

    /**
     * 단일 라우팅 계획을 실행한다.
     *
     * @param plan 실행할 라우팅 계획
     * @param context 실행 컨텍스트
     * @return 표준화된 downstream 호출 결과
     */
    DownstreamCallResult invoke(RoutingPlan plan, SupervisorPlanningContext context);
}
