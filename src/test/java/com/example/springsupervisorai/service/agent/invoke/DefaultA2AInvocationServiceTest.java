package com.example.springsupervisorai.service.agent.invoke;

import com.example.springsupervisorai.a2a.A2AJsonRpcClient;
import com.example.springsupervisorai.a2a.A2ARequestMapper;
import com.example.springsupervisorai.config.A2aSupervisorRoutingProperties;
import com.example.springsupervisorai.exception.DownstreamA2AException;
import com.example.springsupervisorai.model.RoutingPlan;
import com.example.springsupervisorai.model.SupervisorErrorCode;
import com.example.springsupervisorai.model.SupervisorInvocationStatus;
import com.example.springsupervisorai.model.SupervisorPlanningContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultA2AInvocationServiceTest {

    @Test
    void invokeShouldShortCircuitWhenCircuitIsOpen() {
        A2aSupervisorRoutingProperties properties = new A2aSupervisorRoutingProperties();
        A2aSupervisorRoutingProperties.Route productRoute = new A2aSupervisorRoutingProperties.Route();
        productRoute.setEndpoint("http://localhost:8082/a2a/product");
        properties.setRouting(Map.of("product", productRoute));
        properties.getRetry().setMaxRetries(0);
        properties.getCircuitBreaker().setEnabled(true);
        properties.getCircuitBreaker().setFailureThreshold(1);
        properties.getCircuitBreaker().setOpenDurationMs(60_000);

        A2AClientRegistry clientRegistry = new A2AClientRegistry(properties);
        A2ARequestMapper requestMapper = new A2ARequestMapper(new ObjectMapper());
        A2AJsonRpcClient jsonRpcClient = mock(A2AJsonRpcClient.class);
        when(jsonRpcClient.call(any(), any(), any()))
                .thenThrow(new DownstreamA2AException("mock downstream failure"));

        DefaultA2AInvocationService service = new DefaultA2AInvocationService(clientRegistry, requestMapper, jsonRpcClient);
        SupervisorPlanningContext context = new SupervisorPlanningContext("s1", "상품 조회", "openai");
        RoutingPlan plan = new RoutingPlan("product", "message/send", "test", 1, Map.of());

        var first = service.invoke(plan, context);
        var second = service.invoke(plan, context);

        assertThat(first.status()).isEqualTo(SupervisorInvocationStatus.FAILED.value());
        assertThat(first.errorCode()).isEqualTo(SupervisorErrorCode.DOWNSTREAM_UNAVAILABLE.value());
        assertThat(second.status()).isEqualTo(SupervisorInvocationStatus.FAILED.value());
        assertThat(second.errorCode()).isEqualTo(SupervisorErrorCode.CIRCUIT_OPEN.value());
        verify(jsonRpcClient, times(2)).call(any(), any(), any());
    }

    @Test
    void invokeShouldParseHandoffDirectiveFromDownstreamResult() {
        A2aSupervisorRoutingProperties properties = new A2aSupervisorRoutingProperties();
        A2aSupervisorRoutingProperties.Route productRoute = new A2aSupervisorRoutingProperties.Route();
        productRoute.setEndpoint("http://localhost:8082/a2a/product");
        properties.setRouting(Map.of("product", productRoute));

        A2AClientRegistry clientRegistry = new A2AClientRegistry(properties);
        ObjectMapper objectMapper = new ObjectMapper();
        A2ARequestMapper requestMapper = new A2ARequestMapper(objectMapper);
        A2AJsonRpcClient jsonRpcClient = mock(A2AJsonRpcClient.class);

        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode result = response.putObject("result");
        result.put("id", "task-1");
        result.put("status", "COMPLETED");
        ObjectNode handoff = result.putObject("handoff");
        handoff.put("requested", true);
        handoff.put("nextAgentKey", "search");
        handoff.put("method", "message/send");
        handoff.put("reason", "delegate_to_search");
        handoff.putObject("arguments").put("query", "테스트");

        when(jsonRpcClient.call(any(), any(), any())).thenReturn(response);

        DefaultA2AInvocationService service = new DefaultA2AInvocationService(clientRegistry, requestMapper, jsonRpcClient);
        SupervisorPlanningContext context = new SupervisorPlanningContext("s1", "상품 조회", "openai");
        RoutingPlan plan = new RoutingPlan("product", "message/send", "test", 1, Map.of());

        var invokeResult = service.invoke(plan, context);

        assertThat(invokeResult.handoffRequested()).isTrue();
        assertThat(invokeResult.nextAgentKey()).isEqualTo("search");
        assertThat(invokeResult.handoffMethod()).isEqualTo("message/send");
        assertThat(invokeResult.handoffReason()).isEqualTo("delegate_to_search");
        assertThat(invokeResult.handoffArguments()).containsEntry("query", "테스트");
    }

    @Test
    void invokeShouldNormalizeStreamErrorPayloadAsFailed() {
        A2aSupervisorRoutingProperties properties = new A2aSupervisorRoutingProperties();
        A2aSupervisorRoutingProperties.Route reservationRoute = new A2aSupervisorRoutingProperties.Route();
        reservationRoute.setEndpoint("http://localhost:8082/a2a/reservation");
        properties.setRouting(Map.of("reservation", reservationRoute));

        A2AClientRegistry clientRegistry = new A2AClientRegistry(properties);
        A2ARequestMapper requestMapper = new A2ARequestMapper(new ObjectMapper());
        A2AJsonRpcClient jsonRpcClient = mock(A2AJsonRpcClient.class);
        when(jsonRpcClient.callStream(any(), any(), any()))
                .thenReturn("[ERROR][DOWNSTREAM_STREAM_FAILED] timeout");

        DefaultA2AInvocationService service = new DefaultA2AInvocationService(clientRegistry, requestMapper, jsonRpcClient);
        SupervisorPlanningContext context = new SupervisorPlanningContext("s1", "예약 요청", "openai");
        RoutingPlan plan = new RoutingPlan("reservation", "message/stream", "test", 1, Map.of());

        var invokeResult = service.invoke(plan, context);

        assertThat(invokeResult.status()).isEqualTo(SupervisorInvocationStatus.FAILED.value());
        assertThat(invokeResult.errorCode()).isEqualTo(SupervisorErrorCode.DOWNSTREAM_ERROR.value());
        assertThat(invokeResult.errorMessage()).contains("payload");
    }

    @Test
    void invokeShouldUseEmbeddedErrorCodeWhenResultPayloadContainsFailureFields() {
        A2aSupervisorRoutingProperties properties = new A2aSupervisorRoutingProperties();
        A2aSupervisorRoutingProperties.Route reservationRoute = new A2aSupervisorRoutingProperties.Route();
        reservationRoute.setEndpoint("http://localhost:8082/a2a/reservation");
        properties.setRouting(Map.of("reservation", reservationRoute));

        A2AClientRegistry clientRegistry = new A2AClientRegistry(properties);
        ObjectMapper objectMapper = new ObjectMapper();
        A2ARequestMapper requestMapper = new A2ARequestMapper(objectMapper);
        A2AJsonRpcClient jsonRpcClient = mock(A2AJsonRpcClient.class);

        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode result = response.putObject("result");
        result.put("id", "task-2");
        result.put("status", "COMPLETED");
        result.put("errorCode", "DOWNSTREAM_TIMEOUT");
        result.put("errorMessage", "timeout from downstream");

        when(jsonRpcClient.call(any(), any(), any())).thenReturn(response);

        DefaultA2AInvocationService service = new DefaultA2AInvocationService(clientRegistry, requestMapper, jsonRpcClient);
        SupervisorPlanningContext context = new SupervisorPlanningContext("s1", "예약 요청", "openai");
        RoutingPlan plan = new RoutingPlan("reservation", "message/send", "test", 1, Map.of());

        var invokeResult = service.invoke(plan, context);

        assertThat(invokeResult.status()).isEqualTo(SupervisorInvocationStatus.FAILED.value());
        assertThat(invokeResult.errorCode()).isEqualTo("DOWNSTREAM_TIMEOUT");
        assertThat(invokeResult.errorMessage()).isEqualTo("timeout from downstream");
    }
}
