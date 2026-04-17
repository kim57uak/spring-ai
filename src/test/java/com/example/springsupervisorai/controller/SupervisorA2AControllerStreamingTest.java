package com.example.springsupervisorai.controller;

import com.example.springsupervisorai.a2a.A2AResponseMapper;
import com.example.springsupervisorai.a2a.dto.JsonRpcRequest;
import com.example.springsupervisorai.config.SupervisorStreamProperties;
import com.example.springsupervisorai.model.SupervisorOutputEvent;
import com.example.springsupervisorai.service.SupervisorAgentService;
import com.example.springsupervisorai.service.SupervisorProgressSupport;
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
import static org.mockito.Mockito.verify;
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
        when(supervisorAgentService.streamEvents("session-1", "timeout test", "openai")).thenReturn(Flux.never());

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

    @Test
    void handleMainStreamShouldMapA2uiEventAtControllerBoundary() {
        SupervisorAgentService supervisorAgentService = mock(SupervisorAgentService.class);
        A2AResponseMapper responseMapper = mock(A2AResponseMapper.class);
        ObjectMapper objectMapper = new ObjectMapper();
        PromptInjectionGuard guard = new PromptInjectionGuard();
        SupervisorA2ARequestValidator validator = new SupervisorA2ARequestValidator();
        SupervisorStreamProperties streamProperties = new SupervisorStreamProperties();
        streamProperties.setTimeoutMs(1_000);
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
        when(supervisorAgentService.streamEvents("session-1", "show a2ui", "openai")).thenReturn(Flux.just(
                SupervisorOutputEvent.text("hello"),
                SupervisorOutputEvent.a2ui("{\"type\":\"card\"}")
        ));

        JsonRpcRequest request = new JsonRpcRequest(
                "2.0",
                "req-2",
                "message/stream",
                objectMapper.valueToTree(Map.of("messageText", "show a2ui", "model", "openai"))
        );

        List<String> events = controller.handleMainStream(request, session)
                .collectList()
                .block(Duration.ofSeconds(3));

        assertThat(events).isNotNull();
        String joined = String.join("", events);
        assertThat(joined).contains("event: chunk");
        assertThat(joined).contains("hello");
        assertThat(joined).contains("event: a2ui");
        assertThat(joined).contains("\"{\\\"type\\\":\\\"card\\\"}\"");
    }

    @Test
    void handleMainStreamShouldStreamReviewDecisionEvents() {
        SupervisorAgentService supervisorAgentService = mock(SupervisorAgentService.class);
        A2AResponseMapper responseMapper = mock(A2AResponseMapper.class);
        ObjectMapper objectMapper = new ObjectMapper();
        PromptInjectionGuard guard = new PromptInjectionGuard();
        SupervisorA2ARequestValidator validator = new SupervisorA2ARequestValidator();
        SupervisorStreamProperties streamProperties = new SupervisorStreamProperties();
        streamProperties.setTimeoutMs(1_000);
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
        when(supervisorAgentService.decideReviewStream("session-1", "sup-task-1", "APPROVE", "approved_from_ui", "dec-1"))
                .thenReturn(Flux.just(
                        SupervisorOutputEvent.progress(SupervisorProgressSupport.event("hitl", 12, "승인이 완료되었습니다.", Map.of())),
                        SupervisorOutputEvent.text("후속 실행 결과")
                ));

        JsonRpcRequest request = new JsonRpcRequest(
                "2.0",
                "req-3",
                "tasks/review/decide/stream",
                objectMapper.valueToTree(Map.of(
                        "id", "sup-task-1",
                        "decision", "APPROVE",
                        "reason", "approved_from_ui",
                        "decisionId", "dec-1"
                ))
        );

        List<String> events = controller.handleMainStream(request, session)
                .collectList()
                .block(Duration.ofSeconds(3));

        assertThat(events).isNotNull();
        String joined = String.join("", events);
        assertThat(joined).contains("event: chunk");
        assertThat(joined).contains("승인이 완료되었습니다");
        assertThat(joined).contains("후속 실행 결과");
        assertThat(joined).contains("event: done");
        verify(supervisorAgentService).decideReviewStream("session-1", "sup-task-1", "APPROVE", "approved_from_ui", "dec-1");
    }
}
