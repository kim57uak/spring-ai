package com.example.springsupervisorai.service;

import com.example.springsupervisorai.config.A2aSupervisorRoutingProperties;
import com.example.springsupervisorai.model.DownstreamCallResult;
import com.example.springsupervisorai.model.RoutingPlan;
import com.example.springsupervisorai.model.SupervisorAgentRequest;
import com.example.springsupervisorai.model.SupervisorInvocationStatus;
import com.example.springsupervisorai.model.SupervisorPlanningContext;
import com.example.springsupervisorai.service.agent.graph.SupervisorBatchExecutionPolicy;
import com.example.springsupervisorai.service.agent.graph.SupervisorPlanRunner;
import com.example.springsupervisorai.service.agent.invoke.A2AInvocationService;
import com.example.springsupervisorai.service.agent.swarm.SupervisorSwarmCoordinator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SupervisorFallbackInvokeServiceHandoffTest {

    @Mock
    private SupervisorBatchExecutionPolicy batchExecutionPolicy;

    @Mock
    private SupervisorPlanRunner planRunner;

    @Mock
    private SupervisorSwarmCoordinator swarmCoordinator;

    @InjectMocks
    private SupervisorFallbackInvokeService fallbackService;

    @Captor
    private ArgumentCaptor<List<DownstreamCallResult>> batchResultCaptor;

    @Test
    void invokeIfRequiredShouldAccumulateHandoffInContext() {
        SupervisorAgentRequest request = new SupervisorAgentRequest("session-1", "find product", "openai");
        String taskId = "task-1";
        AtomicBoolean canceled = new AtomicBoolean(false);
        SupervisorPlanningContext context = new SupervisorPlanningContext("session-1", "find product", "openai");
        RoutingPlan plan1 = new RoutingPlan("product", "message/send", "product info", 1, Map.of());
        RoutingPlan plan2 = new RoutingPlan("search", "message/send", "search query", 2, Map.of());
        context.setRoutingPlans(List.of(plan1, plan2));

        List<DownstreamCallResult> batch1 = List.of(
                new DownstreamCallResult("product", "task-1", SupervisorInvocationStatus.COMPLETED.value(),
                        "{\"product\":\"phone\"}", "", "",
                        true, "search", "message/send", "delegate_to_search", Map.of("q", "phone"))
        );

        when(batchExecutionPolicy.maxIterations()).thenReturn(5);
        when(batchExecutionPolicy.resolveBatch(context, 0)).thenReturn(List.of(plan1));
        when(batchExecutionPolicy.resolveBatch(context, 1)).thenReturn(List.of(plan2));
        when(planRunner.invokeBatch(List.of(plan1), context)).thenReturn(batch1);

        List<DownstreamCallResult> batch2 = List.of(
                new DownstreamCallResult("search", "task-2", SupervisorInvocationStatus.COMPLETED.value(),
                        "{\"result\":\"found\"}", "", "")
        );
        when(planRunner.invokeBatch(List.of(plan2), context)).thenReturn(batch2);

        fallbackService.invokeIfRequired(request, taskId, canceled, context,
                (stage, progress, message, metadata) -> {}, () -> canceled.get());

        assertThat(context.getResults()).hasSize(2);
        DownstreamCallResult first = context.getResults().get(0);
        assertThat(first.handoffRequested()).isTrue();
        assertThat(first.nextAgentKey()).isEqualTo("search");
        assertThat(first.handoffMethod()).isEqualTo("message/send");
        assertThat(first.handoffArguments()).containsEntry("q", "phone");

        verify(swarmCoordinator, times(2)).recordInvocationBatch(eq(taskId), eq("session-1"), batchResultCaptor.capture());
        List<DownstreamCallResult> recordedBatch0 = batchResultCaptor.getAllValues().get(0);
        assertThat(recordedBatch0.get(0).handoffRequested()).isTrue();
        assertThat(recordedBatch0.get(0).nextAgentKey()).isEqualTo("search");
        List<DownstreamCallResult> recordedBatch1 = batchResultCaptor.getAllValues().get(1);
        assertThat(recordedBatch1.get(0).handoffRequested()).isFalse();
    }

    @Test
    void invokeIfRequiredShouldSkipWhenContextHasResults() {
        SupervisorAgentRequest request = new SupervisorAgentRequest("session-1", "hello", "openai");
        String taskId = "task-1";
        AtomicBoolean canceled = new AtomicBoolean(false);
        SupervisorPlanningContext context = new SupervisorPlanningContext("session-1", "hello", "openai");
        context.addResult(new DownstreamCallResult("existing", "", SupervisorInvocationStatus.COMPLETED.value(), "", "", ""));
        context.setRoutingPlans(List.of(new RoutingPlan("product", "message/send", "unused", 1, Map.of())));

        fallbackService.invokeIfRequired(request, taskId, canceled, context,
                (stage, progress, message, metadata) -> {}, () -> canceled.get());

        assertThat(context.getResults()).hasSize(1);
    }
}
