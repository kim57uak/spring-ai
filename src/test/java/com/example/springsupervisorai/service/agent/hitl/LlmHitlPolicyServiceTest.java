package com.example.springsupervisorai.service.agent.hitl;

import com.example.springsupervisorai.config.A2aSupervisorRoutingProperties;
import com.example.springsupervisorai.config.SupervisorPromptProperties;
import com.example.springsupervisorai.model.HitlPolicyResult;
import com.example.springsupervisorai.service.agent.runtime.SupervisorLlmRuntime;
import com.example.springsupervisorai.service.agent.security.PromptInjectionGuard;
import com.example.springsupervisorai.service.prompt.SupervisorPromptRenderService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmHitlPolicyServiceTest {

    @Test
    void evaluateShouldMapReasonCodeWithoutExtraLlmCall() {
        SupervisorLlmRuntime llmRuntime = mock(SupervisorLlmRuntime.class);
        when(llmRuntime.complete(anyString(), anyString(), anyString()))
                .thenReturn("""
                        {"intentType":"data_mutation","reviewRequired":true,"reviewReason":"reservation_creation_request","riskScore":0.92}
                        """);

        LlmHitlPolicyService service = new LlmHitlPolicyService(
                llmRuntime,
                new A2aSupervisorRoutingProperties(),
                promptProperties(),
                new SupervisorPromptRenderService(),
                new PromptInjectionGuard(),
                new ObjectMapper()
        );

        HitlPolicyResult result = service.evaluate(
                "s1",
                "예약생성 : 이름 - 김병두 , 판매상품코드 - AAP331260523TG1, 인원1명\n상품복사 : AAP331260523TG1,모든요일, 20261201~20261230",
                "openai"
        );

        assertThat(result.required()).isTrue();
        assertThat(result.policyId()).isEqualTo("HITL-POL-DATA-MUTATION");
        assertThat(result.reason()).contains("예약 생성 요청");
        verify(llmRuntime, times(1)).complete(anyString(), anyString(), anyString());
    }

    @Test
    void evaluateShouldReturnNotRequiredForReadOnlyWithoutReasonRefinement() {
        SupervisorLlmRuntime llmRuntime = mock(SupervisorLlmRuntime.class);
        when(llmRuntime.complete(anyString(), anyString(), anyString()))
                .thenReturn(
                        """
                                {"intentType":"read_only","reviewRequired":false,"reviewReason":"조회성 요청입니다.","riskScore":0.10}
                                """
                );

        LlmHitlPolicyService service = new LlmHitlPolicyService(
                llmRuntime,
                new A2aSupervisorRoutingProperties(),
                promptProperties(),
                new SupervisorPromptRenderService(),
                new PromptInjectionGuard(),
                new ObjectMapper()
        );

        HitlPolicyResult result = service.evaluate("s1", "최근 여행 트렌드 알려줘", "openai");

        assertThat(result.required()).isFalse();
        assertThat(result.reason()).isBlank();
        verify(llmRuntime, times(1)).complete(anyString(), anyString(), anyString());
    }

    private static SupervisorPromptProperties promptProperties() {
        SupervisorPromptProperties properties = new SupervisorPromptProperties();
        properties.setHitlPolicySystem("hitl-system");
        properties.setHitlPolicyTemplate("""
                {hitlPolicySystem}
                user={userMessage}
                today={today}
                """);
        properties.setHitlPolicyRepairTemplate("""
                repair={invalidOutput}
                """);
        return properties;
    }
}
