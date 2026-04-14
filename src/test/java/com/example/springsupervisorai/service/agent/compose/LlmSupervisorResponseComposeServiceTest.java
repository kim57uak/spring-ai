package com.example.springsupervisorai.service.agent.compose;

import com.example.springsupervisorai.config.A2aSupervisorRoutingProperties;
import com.example.springsupervisorai.config.SupervisorPromptProperties;
import com.example.springsupervisorai.model.DownstreamCallResult;
import com.example.springsupervisorai.model.SupervisorPlanningContext;
import com.example.springsupervisorai.service.agent.runtime.SupervisorLlmRuntime;
import com.example.springsupervisorai.service.agent.security.PromptInjectionGuard;
import com.example.springsupervisorai.service.prompt.SupervisorPromptRenderService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LlmSupervisorResponseComposeServiceTest {

    @Test
    void streamComposeBypassesLlmWhenOnlyFailuresExist() {
        SupervisorLlmRuntime llmRuntime = mock(SupervisorLlmRuntime.class);
        LlmSupervisorResponseComposeService service = new LlmSupervisorResponseComposeService(
                llmRuntime,
                new A2aSupervisorRoutingProperties(),
                composePromptProperties(),
                new SupervisorPromptRenderService(),
                new PromptInjectionGuard()
        );
        SupervisorPlanningContext context = new SupervisorPlanningContext("s1", "연차 신청", "openai");
        context.addResult(new DownstreamCallResult(
                "reservation",
                "t1",
                "COMPLETED",
                "[ERROR][REQUEST_FAILED] unsupported request",
                "",
                ""
        ));

        List<String> chunks = service.streamCompose(context).collectList().block();
        String merged = String.join("", chunks == null ? List.of() : chunks);

        assertThat(merged).contains("요청을 완료하지 못했습니다.");
        assertThat(merged).contains("FAILED");
        verifyNoInteractions(llmRuntime);
    }

    @Test
    void streamComposeUsesLlmWhenSuccessExistsAndIncludesNormalizedFieldsInPrompt() {
        SupervisorLlmRuntime llmRuntime = mock(SupervisorLlmRuntime.class);
        when(llmRuntime.stream(anyString(), eq("openai"), eq("s1"))).thenReturn(Flux.just("정상 응답"));

        LlmSupervisorResponseComposeService service = new LlmSupervisorResponseComposeService(
                llmRuntime,
                new A2aSupervisorRoutingProperties(),
                composePromptProperties(),
                new SupervisorPromptRenderService(),
                new PromptInjectionGuard()
        );
        SupervisorPlanningContext context = new SupervisorPlanningContext("s1", "상품 추천", "openai");
        context.addResult(new DownstreamCallResult(
                "product",
                "t2",
                "COMPLETED",
                "{\"status\":\"COMPLETED\",\"items\":[]}",
                "",
                ""
        ));

        List<String> chunks = service.streamCompose(context).collectList().block();
        String merged = String.join("", chunks == null ? List.of() : chunks);

        assertThat(merged).isEqualTo("정상 응답");

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(llmRuntime).stream(promptCaptor.capture(), eq("openai"), eq("s1"));
        String prompt = promptCaptor.getValue();
        assertThat(prompt).contains("normalizedOutcome=SUCCESS");
        assertThat(prompt).contains("successCount=1");
    }

    @Test
    void streamComposeKeepsMixedOutcomeAsMixedAndDoesNotBypassLlm() {
        SupervisorLlmRuntime llmRuntime = mock(SupervisorLlmRuntime.class);
        when(llmRuntime.stream(anyString(), eq("openai"), eq("s1"))).thenReturn(Flux.just("부분 성공 응답"));

        LlmSupervisorResponseComposeService service = new LlmSupervisorResponseComposeService(
                llmRuntime,
                new A2aSupervisorRoutingProperties(),
                composePromptProperties(),
                new SupervisorPromptRenderService(),
                new PromptInjectionGuard()
        );
        SupervisorPlanningContext context = new SupervisorPlanningContext("s1", "복합 요청", "openai");
        context.addResult(new DownstreamCallResult(
                "product",
                "t2",
                "COMPLETED",
                "{\"status\":\"COMPLETED\"}",
                "",
                ""
        ));
        context.addResult(new DownstreamCallResult(
                "reservation",
                "t3",
                "COMPLETED",
                "[ERROR][REQUEST_FAILED] unsupported request",
                "",
                ""
        ));

        List<String> chunks = service.streamCompose(context).collectList().block();
        String merged = String.join("", chunks == null ? List.of() : chunks);

        assertThat(merged).isEqualTo("부분 성공 응답");
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(llmRuntime).stream(promptCaptor.capture(), eq("openai"), eq("s1"));
        String prompt = promptCaptor.getValue();
        assertThat(prompt).contains("overallOutcome=MIXED");
        assertThat(prompt).contains("successCount=1");
        assertThat(prompt).contains("failedCount=1");
    }

    private static SupervisorPromptProperties composePromptProperties() {
        SupervisorPromptProperties promptProperties = new SupervisorPromptProperties();
        promptProperties.setComposeSystem("compose-system");
        promptProperties.setComposeTemplate("""
                {composeSystem}
                user={userMessage}
                history={history}
                results={downstreamResults}
                summary={downstreamOutcomeSummary}
                """);
        return promptProperties;
    }
}
