package com.example.springsupervisorai.service.agent.runtime;

import com.example.springsupervisorai.exception.SupervisorChatProcessingException;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultSupervisorLlmRuntimeTest {

    @Test
    void completeDelegatesToGateway() {
        SupervisorChatGateway gateway = mock(SupervisorChatGateway.class);
        when(gateway.complete("prompt", "openai", "s-1")).thenReturn("ok");
        DefaultSupervisorLlmRuntime runtime = new DefaultSupervisorLlmRuntime(gateway);

        String response = runtime.complete("prompt", "openai", "s-1");

        assertThat(response).isEqualTo("ok");
    }

    @Test
    void completeThrowsWhenGatewayReturnsNull() {
        SupervisorChatGateway gateway = mock(SupervisorChatGateway.class);
        when(gateway.complete("prompt", "openai", "s-1")).thenReturn(null);
        DefaultSupervisorLlmRuntime runtime = new DefaultSupervisorLlmRuntime(gateway);

        assertThatThrownBy(() -> runtime.complete("prompt", "openai", "s-1"))
                .isInstanceOf(SupervisorChatProcessingException.class)
                .hasMessageContaining("Supervisor LLM call failed");
    }

    @Test
    void streamWrapsErrorsToSupervisorException() {
        SupervisorChatGateway gateway = mock(SupervisorChatGateway.class);
        when(gateway.stream("prompt", "openai", "s-1"))
                .thenReturn(Flux.error(new RuntimeException("boom")));
        DefaultSupervisorLlmRuntime runtime = new DefaultSupervisorLlmRuntime(gateway);

        assertThatThrownBy(() -> runtime.stream("prompt", "openai", "s-1").collectList().block())
                .isInstanceOf(SupervisorChatProcessingException.class)
                .hasMessageContaining("Supervisor LLM stream failed");
    }
}
