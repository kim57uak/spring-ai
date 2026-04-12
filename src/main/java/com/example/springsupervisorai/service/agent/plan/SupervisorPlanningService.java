package com.example.springsupervisorai.service.agent.plan;

import com.example.springsupervisorai.model.RoutingPlan;
import com.example.springsupervisorai.model.SupervisorPlanningContext;

import java.util.List;

/**
 * Supervisor 라우팅 계획 생성 포트.
 * <p>
 * 입력 컨텍스트를 해석해 downstream 호출 계획 목록을 반환한다.
 */
public interface SupervisorPlanningService {

    /**
     * 라우팅 계획을 생성한다.
     *
     * @param context planner 입력 컨텍스트
     * @return 우선순위가 포함된 routing plan 목록
     */
    List<RoutingPlan> plan(SupervisorPlanningContext context);
}
