package com.example.springai.service.agent.execute;

import com.example.springai.model.agent.PlanningContext;
import com.example.springai.model.agent.ToolExecutionResult;
import com.example.springai.model.agent.ToolPlan;

/**
 * 계획된 도구를 실제로 실행하는 서비스 계약.
 * <p>
 * 실행 결과는 ToolExecutionResult로 표준화해 반환한다.
 */
public interface ToolExecutionService {

    /**
     * 단일 도구 계획을 실행하고 결과를 표준 결과 객체로 반환한다.
     * <p>
     * 구현체는 스코프 검증, 파라미터 구성, 오류 매핑을 포함할 수 있다.
     */
    ToolExecutionResult execute(ToolPlan plan, PlanningContext context);
}
