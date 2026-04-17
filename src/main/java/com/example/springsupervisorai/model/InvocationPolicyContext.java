package com.example.springsupervisorai.model;

/**
 * Input model for downstream invocation policy and execution.
 *
 * @param plan routing plan to invoke
 * @param planningContext current supervisor planning context
 */
public record InvocationPolicyContext(
        RoutingPlan plan,
        SupervisorPlanningContext planningContext
) {

    /**
     * Creates a null-safe invocation context.
     */
    public static InvocationPolicyContext of(RoutingPlan plan, SupervisorPlanningContext planningContext) {
        return new InvocationPolicyContext(plan, planningContext);
    }
}
