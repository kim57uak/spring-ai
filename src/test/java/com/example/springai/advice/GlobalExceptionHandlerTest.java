package com.example.springai.advice;

import com.example.springai.a2a.dto.JsonRpcResponse;
import com.example.springai.dto.ErrorResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void handleRuntimeMapsA2aRoutingExceptionToInvalidParamsForA2aRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/a2a/supervisor");
        RuntimeException exception = new A2ARoutingException("blocked-agent");

        ResponseEntity<?> response = handler.handleRuntime(exception, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isInstanceOf(JsonRpcResponse.class);
        JsonRpcResponse body = (JsonRpcResponse) response.getBody();
        assertThat(body.error()).isNotNull();
        assertThat(body.error().code()).isEqualTo(-32602);
        assertThat(body.error().message()).isEqualTo("blocked-agent");
    }

    @Test
    void handleRuntimeMapsDownstreamExceptionToBadGatewayForHttpRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/chat");
        RuntimeException exception = new DownstreamA2AException("downstream-failed");

        ResponseEntity<?> response = handler.handleRuntime(exception, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody()).isInstanceOf(ErrorResponse.class);
        ErrorResponse body = (ErrorResponse) response.getBody();
        assertThat(body.error()).isEqualTo("외부 서비스 호출 중 오류가 발생했습니다.");
    }

    @Test
    void handleRuntimeRethrowsUnknownRuntimeException() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/chat");

        assertThatThrownBy(() -> handler.handleRuntime(new RuntimeException("unknown"), request))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("unknown");
    }

    @Test
    void handleRuntimeMapsSupervisorExceptionToA2aInternalError() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/a2a/supervisor");
        RuntimeException exception = new SupervisorChatProcessingException("compose-failed");

        ResponseEntity<?> response = handler.handleRuntime(exception, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody()).isInstanceOf(JsonRpcResponse.class);
        JsonRpcResponse body = (JsonRpcResponse) response.getBody();
        assertThat(body.error()).isNotNull();
        assertThat(body.error().code()).isEqualTo(-32603);
        assertThat(body.error().message()).isEqualTo("Supervisor response compose failed");
    }

    private static final class A2ARoutingException extends RuntimeException {
        private A2ARoutingException(String message) {
            super(message);
        }
    }

    private static final class DownstreamA2AException extends RuntimeException {
        private DownstreamA2AException(String message) {
            super(message);
        }
    }

    private static final class SupervisorChatProcessingException extends RuntimeException {
        private SupervisorChatProcessingException(String message) {
            super(message);
        }
    }
}
