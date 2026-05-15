package com.example.springsupervisorai.model;

/**
 * Downstream invocation 정책 및 실행 입력 모델.
 *
 * @param plan 호출할 routing plan
 * @param planningContext 현재 supervisor planning 컨텍스트
 */
public record InvocationPolicyContext(
        RoutingPlan plan,
        SupervisorPlanningContext planningContext
) {

    /**
     * 널-세이프 invocation 컨텍스트를 생성한다.
     */
    public static InvocationPolicyContext of(RoutingPlan plan, SupervisorPlanningContext planningContext) {
        return new InvocationPolicyContext(plan, planningContext);
    }
}
