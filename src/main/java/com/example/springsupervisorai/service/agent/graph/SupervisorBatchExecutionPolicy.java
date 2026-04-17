package com.example.springsupervisorai.service.agent.graph;

import com.example.springsupervisorai.config.A2aSupervisorRoutingProperties;
import com.example.springsupervisorai.model.RoutingPlan;
import com.example.springsupervisorai.model.SupervisorPlanningContext;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Resolves which routing plans should be executed in the current graph batch.
 */
@Component
public class SupervisorBatchExecutionPolicy {

    private static final int MAX_ITERATIONS = 5;

    private final A2aSupervisorRoutingProperties routingProperties;

    public SupervisorBatchExecutionPolicy(A2aSupervisorRoutingProperties routingProperties) {
        this.routingProperties = routingProperties;
    }

    public int maxIterations() {
        return MAX_ITERATIONS;
    }

    public int normalizedConcurrency() {
        A2aSupervisorRoutingProperties.Execution execution = routingProperties.getExecution();
        if (execution == null) {
            return 1;
        }
        return Math.max(1, execution.getMaxConcurrency());
    }

    public List<RoutingPlan> resolveBatch(SupervisorPlanningContext context, int fromIndex) {
        if (fromIndex >= context.getRoutingPlans().size() || fromIndex >= MAX_ITERATIONS) {
            return List.of();
        }
        int upper = Math.min(context.getRoutingPlans().size(), MAX_ITERATIONS);
        int toIndex = Math.min(upper, fromIndex + normalizedConcurrency());
        if (fromIndex >= toIndex) {
            return List.of();
        }
        return context.getRoutingPlans().subList(fromIndex, toIndex);
    }
}
