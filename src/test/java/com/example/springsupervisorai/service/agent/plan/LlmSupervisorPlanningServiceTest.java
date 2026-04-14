package com.example.springsupervisorai.service.agent.plan;

import com.example.springsupervisorai.config.A2aSupervisorRoutingProperties;
import com.example.springsupervisorai.config.SupervisorPromptProperties;
import com.example.springsupervisorai.model.RoutingPlan;
import com.example.springsupervisorai.model.SupervisorPlanningContext;
import com.example.springsupervisorai.service.agent.invoke.DownstreamAgentCardCache;
import com.example.springsupervisorai.service.agent.runtime.SupervisorLlmRuntime;
import com.example.springsupervisorai.service.agent.security.PromptInjectionGuard;
import com.example.springsupervisorai.service.prompt.SupervisorPromptRenderService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmSupervisorPlanningServiceTest {

    @Test
    void planNormalizesAgentKeyVariantsToRoutingKeys() {
        A2aSupervisorRoutingProperties routingProperties = new A2aSupervisorRoutingProperties();
        routingProperties.setRouting(Map.of(
                "product", route("http://localhost:8082/a2a/product"),
                "search", route("http://localhost:8082/a2a/search")
        ));

        SupervisorPromptProperties promptProperties = new SupervisorPromptProperties();
        promptProperties.setPlanningSystem("system");
        promptProperties.setPlanningTemplate("{planningSystem}\nallowed={allowedAgents}\ncards={agentCards}\nmsg={userMessage}\nhistory={history}");
        promptProperties.setPlanningRepairTemplate("{invalidOutput}");

        SupervisorLlmRuntime llmRuntime = mock(SupervisorLlmRuntime.class);
        when(llmRuntime.complete(anyString(), anyString(), anyString()))
                .thenReturn("""
                        {"complete":false,"plans":[
                          {"agentKey":"Product","method":"SendMessage","reason":"상품 조회","priority":1,"arguments":{"productCode":"AAP331260523TG1"}},
                          {"agentKey":"search-agent","method":"SendMessage","reason":"트렌드 조회","priority":2,"arguments":{"query":"2026년 최신 여행트렌드"}}
                        ]}
                        """);

        DownstreamAgentCardCache cardCache = mock(DownstreamAgentCardCache.class);
        when(cardCache.summarizeForPrompt(anyList())).thenReturn("cards");
        when(cardCache.supportsStreaming(anyString())).thenReturn(false);

        LlmSupervisorPlanningService service = new LlmSupervisorPlanningService(
                llmRuntime,
                promptProperties,
                new SupervisorPromptRenderService(),
                new PromptInjectionGuard(),
                routingProperties,
                cardCache,
                new ObjectMapper()
        );

        List<RoutingPlan> plans = service.plan(new SupervisorPlanningContext("s1", "상품+트렌드 비교", "mistral"));

        assertThat(plans).hasSize(2);
        assertThat(plans.get(0).agentKey()).isEqualTo("product");
        assertThat(plans.get(1).agentKey()).isEqualTo("search");
    }

    @Test
    void planDeduplicatesByAgentKeyOnly() {
        A2aSupervisorRoutingProperties routingProperties = new A2aSupervisorRoutingProperties();
        routingProperties.setRouting(Map.of(
                "product", route("http://localhost:8082/a2a/product")
        ));

        SupervisorPromptProperties promptProperties = new SupervisorPromptProperties();
        promptProperties.setPlanningSystem("system");
        promptProperties.setPlanningTemplate("{planningSystem}\nallowed={allowedAgents}\ncards={agentCards}\nmsg={userMessage}\nhistory={history}");
        promptProperties.setPlanningRepairTemplate("{invalidOutput}");

        SupervisorLlmRuntime llmRuntime = mock(SupervisorLlmRuntime.class);
        when(llmRuntime.complete(anyString(), anyString(), anyString()))
                .thenReturn("""
                        {"complete":false,"plans":[
                          {"agentKey":"product","method":"SendMessage","reason":"상품 조회","priority":1,"arguments":{"intent":"read"}},
                          {"agentKey":"product","method":"GetTask","reason":"추가 조회","priority":2,"arguments":{"id":"task-1"}}
                        ]}
                        """);

        DownstreamAgentCardCache cardCache = mock(DownstreamAgentCardCache.class);
        when(cardCache.summarizeForPrompt(anyList())).thenReturn("cards");
        when(cardCache.supportsStreaming(anyString())).thenReturn(false);

        LlmSupervisorPlanningService service = new LlmSupervisorPlanningService(
                llmRuntime,
                promptProperties,
                new SupervisorPromptRenderService(),
                new PromptInjectionGuard(),
                routingProperties,
                cardCache,
                new ObjectMapper()
        );

        List<RoutingPlan> plans = service.plan(new SupervisorPlanningContext("s1", "조회+생성", "mistral"));

        assertThat(plans).hasSize(1);
        assertThat(plans.get(0).agentKey()).isEqualTo("product");
        assertThat(plans.get(0).method()).isEqualTo("SendMessage");
    }

    @Test
    void planUsesConfiguredHistoryMaxTurns() {
        A2aSupervisorRoutingProperties routingProperties = new A2aSupervisorRoutingProperties();
        routingProperties.setRouting(Map.of("product", route("http://localhost:8082/a2a/product")));
        A2aSupervisorRoutingProperties.History history = new A2aSupervisorRoutingProperties.History();
        history.setMaxTurns(2);
        routingProperties.setHistory(history);

        SupervisorPromptProperties promptProperties = new SupervisorPromptProperties();
        promptProperties.setPlanningSystem("system");
        promptProperties.setPlanningTemplate("{planningSystem}\nmsg={userMessage}\nhistory={history}");
        promptProperties.setPlanningRepairTemplate("{invalidOutput}");

        SupervisorLlmRuntime llmRuntime = mock(SupervisorLlmRuntime.class);
        when(llmRuntime.complete(anyString(), anyString(), anyString()))
                .thenReturn("""
                        {"complete":false,"plans":[{"agentKey":"product","method":"SendMessage","reason":"ok","priority":1}]}
                        """);

        DownstreamAgentCardCache cardCache = mock(DownstreamAgentCardCache.class);
        when(cardCache.summarizeForPrompt(anyList())).thenReturn("cards");
        when(cardCache.supportsStreaming(anyString())).thenReturn(false);

        LlmSupervisorPlanningService service = new LlmSupervisorPlanningService(
                llmRuntime,
                promptProperties,
                new SupervisorPromptRenderService(),
                new PromptInjectionGuard(),
                routingProperties,
                cardCache,
                new ObjectMapper()
        );

        SupervisorPlanningContext context = new SupervisorPlanningContext("s1", "현재 질문", "mistral");
        context.replaceHistory(List.of(
                "user: t1-u", "assistant: t1-a",
                "user: t2-u", "assistant: t2-a",
                "user: t3-u", "assistant: t3-a",
                "user: t4-u", "assistant: t4-a",
                "user: t5-u", "assistant: t5-a",
                "user: t6-u", "assistant: t6-a"
        ));

        service.plan(context);

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(llmRuntime).complete(promptCaptor.capture(), anyString(), anyString());
        String prompt = promptCaptor.getValue();

        assertThat(prompt).contains("t5-u", "t5-a", "t6-u", "t6-a");
        assertThat(prompt).doesNotContain("t1-u", "t2-u", "t3-u", "t4-u");
    }

    @Test
    void planTreatsCompleteTrueAsNoDownstreamEvenWhenPlansExist() {
        A2aSupervisorRoutingProperties routingProperties = new A2aSupervisorRoutingProperties();
        routingProperties.setRouting(Map.of(
                "reservation", route("http://localhost:8082/a2a/reservation")
        ));

        SupervisorPromptProperties promptProperties = new SupervisorPromptProperties();
        promptProperties.setPlanningSystem("system");
        promptProperties.setPlanningTemplate("{planningSystem}\nallowed={allowedAgents}\ncards={agentCards}\nmsg={userMessage}\nhistory={history}");
        promptProperties.setPlanningRepairTemplate("{invalidOutput}");

        SupervisorLlmRuntime llmRuntime = mock(SupervisorLlmRuntime.class);
        when(llmRuntime.complete(anyString(), anyString(), anyString()))
                .thenReturn("""
                        {"complete":true,"plans":[
                          {"agentKey":"reservation","method":"SendMessage","reason":"예약 처리","priority":1}
                        ]}
                        """);

        DownstreamAgentCardCache cardCache = mock(DownstreamAgentCardCache.class);
        when(cardCache.summarizeForPrompt(anyList())).thenReturn("cards");
        when(cardCache.supportsStreaming(anyString())).thenReturn(false);

        LlmSupervisorPlanningService service = new LlmSupervisorPlanningService(
                llmRuntime,
                promptProperties,
                new SupervisorPromptRenderService(),
                new PromptInjectionGuard(),
                routingProperties,
                cardCache,
                new ObjectMapper()
        );

        List<RoutingPlan> plans = service.plan(new SupervisorPlanningContext("s1", "사내 업무 처리 요청", "mistral"));

        assertThat(plans).isEmpty();
        verify(llmRuntime, times(1)).complete(anyString(), anyString(), anyString());
    }

    private static A2aSupervisorRoutingProperties.Route route(String endpoint) {
        A2aSupervisorRoutingProperties.Route route = new A2aSupervisorRoutingProperties.Route();
        route.setEndpoint(endpoint);
        return route;
    }
}
