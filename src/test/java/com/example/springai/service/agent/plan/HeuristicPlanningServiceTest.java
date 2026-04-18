package com.example.springai.service.agent.plan;

import com.example.springai.config.McpProperties;
import com.example.springai.config.PromptProperties;
import com.example.springai.mcp.ToolSchemaRegistry;
import com.example.springai.model.agent.AgentScope;
import com.example.springai.model.agent.PlanningContext;
import com.example.springai.model.agent.ToolPlan;
import com.example.springai.service.agent.prompt.PromptRenderService;
import com.example.springai.service.agent.runtime.AgentLlmRuntime;
import com.example.springai.service.agent.security.PromptInjectionGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HeuristicPlanningServiceTest {

    @Test
    void shouldFallbackToRawPlannerWhenStructuredPlannerReturnsComplete() throws Exception {
        McpProperties properties = reservationServerProperties();
        PromptProperties prompts = promptProperties();

        ToolSchemaRegistry schemaRegistry = mock(ToolSchemaRegistry.class);
        when(schemaRegistry.loadTools(eq("reservation"), any())).thenReturn(List.of(Map.of(
                "name", "createReservation",
                "description", "예약 생성",
                "inputSchema", Map.of("type", "object", "properties", Map.of("request", Map.of("type", "object")))
        )));

        AgentLlmRuntime llmRuntime = mock(AgentLlmRuntime.class);
        when(llmRuntime.completeStructured(anyString(), anyString(), any(), anyString())).thenAnswer(invocation -> {
            Class<?> type = invocation.getArgument(2);
            return newPlannerDecision(type, true, List.of());
        });
        when(llmRuntime.complete(anyString(), anyString(), anyString())).thenReturn(
                """
                {"complete":false,"plans":[{"server":"reservation","tool":"createReservation","reason":"예약 생성 요청","arguments":{"request":{"saleProductCode":"AAP331260523TG1","bookerName":"김병두","contact":"01038569626","headCount":"1","birthDate":"19740308"}}}]}
                """
        );

        HeuristicPlanningService service = new HeuristicPlanningService(
                properties,
                prompts,
                schemaRegistry,
                llmRuntime,
                new PromptInjectionGuard(),
                new PromptRenderService(),
                new ObjectMapper()
        );

        PlanningContext context = new PlanningContext(
                "session-1",
                "예약생성해죠 판매상품코드 AAP331260523TG1 예약자 김병두 연락처 01038569626 인원수 1명 생년월일 19740308",
                "openai"
        );
        context.setScope(AgentScope.unrestricted());

        List<ToolPlan> plans = service.plan(context);

        assertThat(plans).hasSize(1);
        assertThat(plans.get(0).toolRequired()).isTrue();
        assertThat(plans.get(0).serverName()).isEqualTo("reservation");
        assertThat(plans.get(0).toolName()).isEqualTo("createReservation");
    }

    private Object newPlannerDecision(Class<?> decisionClass, Boolean complete, List<?> plans) throws Exception {
        Constructor<?> constructor = decisionClass.getDeclaredConstructor(Boolean.class, List.class);
        constructor.setAccessible(true);
        return constructor.newInstance(complete, plans);
    }

    private McpProperties reservationServerProperties() {
        McpProperties properties = new McpProperties();
        McpProperties.ServerConfig reservation = new McpProperties.ServerConfig();
        reservation.setCapabilities(List.of("action-execution"));
        reservation.setAllowTools(List.of("createReservation"));
        properties.setServers(Map.of("reservation", reservation));
        return properties;
    }

    private PromptProperties promptProperties() {
        PromptProperties prompts = new PromptProperties();
        prompts.setAgentSystem("agent-system");
        prompts.setToolChoice("tool-choice");
        prompts.setToolPlanningPromptTemplate(
                "{agentSystem}\n{toolChoice}\n{serverCatalog}\n{userMessage}\n{dateHints}\n{executedTools}\n{latestResult}"
        );
        prompts.setPlannerRepairPromptTemplate("{invalidOutput}");
        prompts.setDateHintsTemplate("today={today}, nextWeek={nextWeekStart}~{nextWeekEnd}");
        return prompts;
    }
}
