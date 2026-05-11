package com.example.springsupervisorai.exception;

import com.example.springsupervisorai.a2a.dto.JsonRpcResponse;
import com.example.springsupervisorai.service.resilience.CircuitBreakerUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.reactive.result.method.annotation.ResponseEntityExceptionHandler;

import java.util.HashMap;
import java.util.Map;

/**
 * 전역 예외 처리기 - JSON-RPC 오류 응답을 표준화합니다.
 */
@ControllerAdvice("supervisorGlobalExceptionHandler")
public class SupervisorGlobalExceptionHandler extends ResponseEntityExceptionHandler {

        private static final Logger logger = LoggerFactory.getLogger(SupervisorGlobalExceptionHandler.class);

        // JSON-RPC 오류 코드 매핑
        private static final Map<Class<? extends Exception>, Integer> ERROR_CODE_MAPPING = new HashMap<>();
        static {
                ERROR_CODE_MAPPING.put(DownstreamA2AException.class, -32002); // 서버 오류
                ERROR_CODE_MAPPING.put(IllegalArgumentException.class, -32602); // 잘못된 파라미터
                ERROR_CODE_MAPPING.put(UnsupportedOperationException.class, -32601); // 메서드 없음
                ERROR_CODE_MAPPING.put(IllegalStateException.class, -32603); // 내부 오류
                ERROR_CODE_MAPPING.put(CircuitBreakerUtils.CircuitBreakerOpenException.class, -32008); // 서킷 브레이커 열림
        }

        @ExceptionHandler(Exception.class)
        public ResponseEntity<JsonRpcResponse> handleException(Exception ex) {
                logger.error("Unhandled exception: ", ex);

                int errorCode = ERROR_CODE_MAPPING.getOrDefault(ex.getClass(), -32603); // 기본값: 내부 오류
                String errorMessage = ex.getMessage() != null ? ex.getMessage() : "Internal server error";

                JsonRpcResponse response = JsonRpcResponse.error(
                                null, // requestId는 알 수 없으므로 null
                                errorCode,
                                errorMessage);

                return ResponseEntity
                                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                                .body(response);
        }

        @ExceptionHandler(DownstreamA2AException.class)
        public ResponseEntity<JsonRpcResponse> handleDownstreamA2AException(DownstreamA2AException ex) {
                logger.warn("Downstream A2A exception: ", ex);

                JsonRpcResponse response = JsonRpcResponse.error(
                                null, // requestId는 알 수 없으므로 null
                                -32002,
                                ex.getMessage());

                return ResponseEntity
                                .status(HttpStatus.BAD_GATEWAY)
                                .body(response);
        }

        @ExceptionHandler(CircuitBreakerUtils.CircuitBreakerOpenException.class)
        public ResponseEntity<JsonRpcResponse> handleCircuitBreakerOpenException(
                        CircuitBreakerUtils.CircuitBreakerOpenException ex) {
                logger.warn("Circuit breaker open: ", ex);

                JsonRpcResponse response = JsonRpcResponse.error(
                                null, // requestId는 알 수 없으므로 null
                                -32008,
                                "Service unavailable: " + ex.getMessage());

                return ResponseEntity
                                .status(HttpStatus.SERVICE_UNAVAILABLE)
                                .body(response);
        }
}