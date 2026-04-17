package com.example.springsupervisorai.service.agent.graph;

import com.example.springsupervisorai.model.DownstreamCallResult;
import com.example.springsupervisorai.model.InvocationPolicyContext;
import com.example.springsupervisorai.model.RoutingPlan;
import com.example.springsupervisorai.model.SupervisorPlanningContext;
import com.example.springsupervisorai.service.agent.invoke.A2AInvocationService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Executes a routing plan batch with the graph's resilience rules.
 */
@Component
public class SupervisorPlanRunner {

    private final A2AInvocationService invocationService;

    public SupervisorPlanRunner(A2AInvocationService invocationService) {
        this.invocationService = invocationService;
    }

    public List<DownstreamCallResult> invokeBatch(List<RoutingPlan> batch, SupervisorPlanningContext context) {
        if (batch.size() == 1) {
            return List.of(invocationService.invoke(InvocationPolicyContext.of(batch.get(0), context)));
        }
        List<CompletableFuture<DownstreamCallResult>> futures = batch.stream()
                .map(plan -> CompletableFuture.supplyAsync(() -> invocationService.invoke(InvocationPolicyContext.of(plan, context)))
                        .exceptionally(error -> new DownstreamCallResult(
                                plan.agentKey(),
                                context.getTaskId(),
                                "FAILED",
                                "",
                                "BATCH_INVOCATION_ERROR",
                                sanitize(error.getMessage())
                        )))
                .toList();
        return futures.stream().map(CompletableFuture::join).toList();
    }

    private String sanitize(String message) {
        if (message == null || message.isBlank()) {
            return "Unknown error";
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
