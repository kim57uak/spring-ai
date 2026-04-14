package com.example.springsupervisorai.controller;

import com.example.springsupervisorai.a2a.A2AResponseMapper;
import com.example.springsupervisorai.a2a.dto.JsonRpcRequest;
import com.example.springsupervisorai.config.SupervisorStreamProperties;
import com.example.springsupervisorai.service.SupervisorAgentService;
import com.example.springsupervisorai.service.agent.security.PromptInjectionGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SupervisorA2AControllerStreamingTest {

    @Test
    void handleMainStreamShouldEmitTimeoutErrorAndDoneEvents() {
        SupervisorAgentService supervisorAgentService = mock(SupervisorAgentService.class);
        A2AResponseMapper responseMapper = mock(A2AResponseMapper.class);
        ObjectMapper objectMapper = new ObjectMapper();
        PromptInjectionGuard guard = new PromptInjectionGuard();
        SupervisorA2ARequestValidator validator = new SupervisorA2ARequestValidator();
        SupervisorStreamProperties streamProperties = new SupervisorStreamProperties();
        streamProperties.setTimeoutMs(50);
        SupervisorA2AController controller = new SupervisorA2AController(
                supervisorAgentService,
                responseMapper,
                objectMapper,
                guard,
                validator,
                streamProperties
        );

        HttpSession session = mock(HttpSession.class);
        when(session.getId()).thenReturn("session-1");
        when(supervisorAgentService.stream("session-1", "timeout test", "openai")).thenReturn(Flux.never());

        JsonRpcRequest request = new JsonRpcRequest(
                "2.0",
                "req-1",
                "message/stream",
                objectMapper.valueToTree(Map.of("messageText", "timeout test", "model", "openai"))
        );

        List<String> events = controller.handleMainStream(request, session)
                .collectList()
                .block(Duration.ofSeconds(3));

        assertThat(events).isNotNull();
        String joined = String.join("", events);
        assertThat(joined).contains("event: error");
        assertThat(joined).contains("\"code\":-32008");
        assertThat(joined).contains("event: done");
        assertThat(joined).contains("\"reason\":\"timeout\"");
    }
}

