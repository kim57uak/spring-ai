package com.example.springsupervisorai.service;

import com.example.springsupervisorai.config.A2aSupervisorRoutingProperties;
import com.example.springsupervisorai.model.DownstreamCallResult;
import com.example.springsupervisorai.model.InvocationPolicyContext;
import com.example.springsupervisorai.model.RoutingPlan;
import com.example.springsupervisorai.model.SupervisorAgentRequest;
import com.example.springsupervisorai.model.SupervisorPlanningContext;
import com.example.springsupervisorai.service.agent.graph.SupervisorBatchExecutionPolicy;
import com.example.springsupervisorai.service.agent.graph.SupervisorPlanRunner;
import com.example.springsupervisorai.service.agent.invoke.A2AInvocationService;
import com.example.springsupervisorai.service.agent.swarm.SupervisorSwarmCoordinator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SupervisorFallbackInvokeService} 회귀 테스트.
 */
class SupervisorFallbackInvokeServiceTest {

    /**
     * fallback invoke가 graph와 동일한 batch 정책을 따라 결과를 누적하는지 검증한다.
     */
    @Test
    void invokeIfRequiredShouldReuseSharedBatchPolicyAndRunner() {
        A2aSupervisorRoutingProperties properties = new A2aSupervisorRoutingProperties();
        A2aSupervisorRoutingProperties.Execution execution = new A2aSupervisorRoutingProperties.Execution();
        execution.setMaxConcurrency(2);
        properties.setExecution(execution);

        A2AInvocationService invocationService = mock(A2AInvocationService.class);
        SupervisorSwarmCoordinator swarmCoordinator = mock(SupervisorSwarmCoordinator.class);
        SupervisorFallbackInvokeService service = new SupervisorFallbackInvokeService(
                new SupervisorBatchExecutionPolicy(properties),
                new SupervisorPlanRunner(invocationService),
                swarmCoordinator
        );

        SupervisorPlanningContext context = new SupervisorPlanningContext("task-1", "s1", "hello", "openai");
        context.setRoutingPlans(List.of(
                new RoutingPlan("product", "message/send", "plan-1", 1, Map.of("q", "a")),
                new RoutingPlan("reservation", "message/send", "plan-2", 2, Map.of("q", "b")),
                new RoutingPlan("search", "message/send", "plan-3", 3, Map.of("q", "c"))
        ));

        when(invocationService.invoke(any(InvocationPolicyContext.class)))
                .thenReturn(
                        new DownstreamCallResult("product", "task-1", "COMPLETED", "p1", "", ""),
                        new DownstreamCallResult("reservation", "task-1", "COMPLETED", "p2", "", ""),
                        new DownstreamCallResult("search", "task-1", "COMPLETED", "p3", "", "")
                );

        List<String> progressMessages = new ArrayList<>();
        service.invokeIfRequired(
                new SupervisorAgentRequest("s1", "hello", "openai"),
                "task-1",
                new AtomicBoolean(false),
                context,
                (stage, progress, message, metadata) -> progressMessages.add(stage + ":" + message),
                () -> false
        );

        assertThat(context.getResults()).hasSize(3);
        assertThat(context.getRoutingIndex()).isEqualTo(3);
        assertThat(progressMessages).anyMatch(message -> message.contains("수동 실행"));
        verify(invocationService, times(3)).invoke(any(InvocationPolicyContext.class));
        verify(swarmCoordinator, times(2)).recordInvocationBatch(eq("task-1"), eq("s1"), any());
    }
}
