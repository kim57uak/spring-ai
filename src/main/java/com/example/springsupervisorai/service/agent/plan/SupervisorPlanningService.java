package com.example.springsupervisorai.service.agent.plan;

import com.example.springsupervisorai.model.RoutingPlan;
import com.example.springsupervisorai.model.SupervisorPlanningContext;

import java.util.List;

/**
 * Supervisor 라우팅 계획 생성 포트.
 * <p>
 * 수신된 planning 컨텍스트를 해석하고 downstream A2A 호출을 구동하는
 * {@link RoutingPlan} 인스턴스의 정렬된 목록을 생성한다.
 * 플래너는 호출할 에이전트와 우선순위 순서를 결정한다.
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
