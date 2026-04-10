package com.example.springai.service.agent.plan;

import com.example.springai.model.agent.PlanningContext;
import com.example.springai.model.agent.ToolPlan;

import java.util.List;

/**
 * 사용자 요청 컨텍스트를 바탕으로 도구 실행 계획을 생성하는 서비스 계약.
 * <p>
 * Planner 구현체는 이 계약을 통해 오케스트레이터와 결합된다.
 */
public interface PlanningService {

    /**
     * 실행 가능한 도구 계획 목록을 반환한다.
     * <p>
     * 반환 목록은 도구 미사용(no-tool) 계획 1개를 포함할 수도 있다.
     */
    List<ToolPlan> plan(PlanningContext context);
}
