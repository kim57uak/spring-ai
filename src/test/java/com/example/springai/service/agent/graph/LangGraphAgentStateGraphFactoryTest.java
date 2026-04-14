package com.example.springai.service.agent.graph;

import com.example.springai.model.agent.AgentGraphState;
import com.example.springai.model.agent.ToolExecutionResult;
import com.example.springai.model.agent.ToolPlan;
import com.example.springai.service.agent.execute.ToolExecutionService;
import com.example.springai.service.agent.plan.PlanningService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class LangGraphAgentStateGraphFactoryTest {

    @Test
    void skipsExecuteWhenPlanRequiredIsFalse() throws Exception {
        PlanningService planningService = mock(PlanningService.class);
        ToolExecutionService toolExecutionService = mock(ToolExecutionService.class);
        when(planningService.plan(any())).thenReturn(List.of(ToolPlan.noTool("no tool required")));

        LangGraphAgentStateGraphFactory factory = new LangGraphAgentStateGraphFactory(planningService, toolExecutionService);
        AgentGraphState result = factory.getCompiledGraph().invoke(baseInput(), org.bsc.langgraph4j.RunnableConfig.builder().threadId("t-1").build())
                .orElseThrow();

        assertThat(result.toPlanningContext().getCurrentNode()).isEqualTo("COMPOSING");
        assertThat(result.toPlanningContext().getExecutionResult().executed()).isFalse();
        verify(toolExecutionService, never()).execute(any(), any());
    }

    @Test
    void executeLoopIsBoundedToFourIterations() throws Exception {
        PlanningService planningService = mock(PlanningService.class);
        ToolExecutionService toolExecutionService = mock(ToolExecutionService.class);
        AtomicInteger planningTurn = new AtomicInteger(0);

        when(planningService.plan(any())).thenAnswer(invocation -> {
            int turn = planningTurn.incrementAndGet();
            return List.of(new ToolPlan(
                    "cap-" + turn,
                    "srv-" + turn,
                    "tool-" + turn,
                    "reason-" + turn,
                    Map.of("turn", turn),
                    true
            ));
        });
        when(toolExecutionService.execute(any(), any())).thenAnswer(invocation -> {
            ToolPlan plan = invocation.getArgument(0, ToolPlan.class);
            return new ToolExecutionResult(
                    plan.serverName(),
                    plan.toolName(),
                    "payload-" + plan.toolName(),
                    plan.arguments(),
                    true,
                    true,
                    false
            );
        });

        LangGraphAgentStateGraphFactory factory = new LangGraphAgentStateGraphFactory(planningService, toolExecutionService);
        AgentGraphState result = factory.getCompiledGraph().invoke(baseInput(), org.bsc.langgraph4j.RunnableConfig.builder().threadId("t-2").build())
                .orElseThrow();

        verify(toolExecutionService, times(4)).execute(any(), any());
        assertThat(result.toPlanningContext().getExecutionResult().executed()).isTrue();
        assertThat(result.toPlanningContext().getToolTrace().size()).isEqualTo(4);
    }

    private Map<String, Object> baseInput() {
        return Map.of(
                AgentGraphState.SESSION_ID, "session-graph-test",
                AgentGraphState.USER_MESSAGE, "테스트 요청",
                AgentGraphState.MODEL, "openai",
                AgentGraphState.HISTORY, List.of(),
                AgentGraphState.CHECKPOINT_ID, "",
                AgentGraphState.CURRENT_NODE, "HISTORY_LOADED",
                AgentGraphState.SCOPE_ALLOWED_SERVERS, List.of(),
                AgentGraphState.SCOPE_ALLOWED_TOOLS, Map.of(),
                AgentGraphState.SCOPE_UNRESTRICTED, true
        );
    }
}
