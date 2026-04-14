package com.example.springai.advice;

import com.example.springai.a2a.dto.JsonRpcResponse;
import com.example.springai.dto.ErrorResponse;
import com.example.springai.exception.ChatProcessingException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.List;

/**
 * HTTP/A2A 공통 예외를 표준 응답 포맷으로 변환하는 전역 핸들러.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final int INVALID_REQUEST = -32600;
    private static final int INVALID_PARAMS = -32602;
    private static final int INTERNAL_ERROR = -32603;
    private static final int DOWNSTREAM_ERROR = -32000;
    private static final List<RuntimeExceptionRule> RUNTIME_EXCEPTION_RULES = List.of(
            new RuntimeExceptionRule(
                    "A2ARoutingException",
                    HttpStatus.BAD_REQUEST,
                    INVALID_PARAMS,
                    null,
                    "입력값을 확인해주세요.",
                    true
            ),
            new RuntimeExceptionRule(
                    "DownstreamA2AException",
                    HttpStatus.BAD_GATEWAY,
                    DOWNSTREAM_ERROR,
                    "Downstream A2A call failed",
                    "외부 서비스 호출 중 오류가 발생했습니다.",
                    false
            ),
            new RuntimeExceptionRule(
                    "SupervisorChatProcessingException",
                    HttpStatus.BAD_GATEWAY,
                    INTERNAL_ERROR,
                    "Supervisor response compose failed",
                    "요청 처리 중 문제가 발생했습니다. 잠시 후 다시 시도해주세요.",
                    false
            )
    );

    /**
     * Bean validation 필드 오류를 400 응답으로 변환한다.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<?> handleValidationException(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fieldError -> fieldError.getDefaultMessage())
                .orElse("Invalid request");
        if (isA2a(request)) {
            return a2aError(HttpStatus.BAD_REQUEST, INVALID_PARAMS, message);
        }
        return ResponseEntity.badRequest().body(new ErrorResponse(message));
    }

    /**
     * 제약조건 위반 예외를 400 응답으로 변환한다.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<?> handleConstraintViolation(ConstraintViolationException ex, HttpServletRequest request) {
        logger.debug("Constraint violation", ex);
        if (isA2a(request)) {
            return a2aError(HttpStatus.BAD_REQUEST, INVALID_PARAMS, "입력값을 확인해주세요.");
        }
        return ResponseEntity.badRequest().body(new ErrorResponse("입력값을 확인해주세요."));
    }

    /**
     * 잘못된 파라미터 예외를 400 응답으로 변환한다.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> handleIllegalArgument(IllegalArgumentException ex, HttpServletRequest request) {
        logger.debug("Illegal argument", ex);
        if (isA2a(request)) {
            return a2aError(HttpStatus.BAD_REQUEST, INVALID_PARAMS, "입력값을 확인해주세요.");
        }
        return ResponseEntity.badRequest().body(new ErrorResponse("입력값을 확인해주세요."));
    }

    /**
     * 일반 채팅 처리 예외를 502 응답으로 변환한다.
     */
    @ExceptionHandler(ChatProcessingException.class)
    public ResponseEntity<?> handleChatProcessing(ChatProcessingException ex, HttpServletRequest request) {
        logger.warn("Chat processing failed", ex);
        if (isA2a(request)) {
            return a2aError(
                    HttpStatus.BAD_GATEWAY,
                    INTERNAL_ERROR,
                    "요청 처리 중 문제가 발생했습니다. 잠시 후 다시 시도해주세요."
            );
        }
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new ErrorResponse("요청 처리 중 문제가 발생했습니다. 잠시 후 다시 시도해주세요."));
    }

    /**
     * 앱 분리 경계의 runtime 예외를 이름 기반으로 A2A 오류 코드에 매핑한다.
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<?> handleRuntime(RuntimeException ex, HttpServletRequest request) {
        String className = ex.getClass().getSimpleName();
        for (RuntimeExceptionRule rule : RUNTIME_EXCEPTION_RULES) {
            if (!rule.simpleClassName().equals(className)) {
                continue;
            }
            logger.warn("{}: {}", rule.logPrefix(), ex.getMessage());
            if (isA2a(request)) {
                String message = rule.useOriginalMessageForA2a()
                        ? sanitizeMessage(ex.getMessage())
                        : rule.a2aMessage();
                return a2aError(rule.status(), rule.a2aCode(), message);
            }
            return ResponseEntity.status(rule.status()).body(new ErrorResponse(rule.httpMessage()));
        }
        throw ex;
    }

    /**
     * 상태 불일치 예외를 503 응답으로 변환한다.
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<?> handleIllegalState(IllegalStateException ex, HttpServletRequest request) {
        logger.warn("Illegal state detected", ex);
        if (isA2a(request)) {
            return a2aError(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    INTERNAL_ERROR,
                    "현재 요청을 처리할 수 없습니다. 잠시 후 다시 시도해주세요."
            );
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorResponse("현재 요청을 처리할 수 없습니다. 잠시 후 다시 시도해주세요."));
    }

    /**
     * 외부 API HTTP 오류를 동일 상태코드로 전달한다.
     */
    @ExceptionHandler(WebClientResponseException.class)
    public ResponseEntity<?> handleWebClientResponse(WebClientResponseException ex, HttpServletRequest request) {
        HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
        logger.warn("Upstream API error status={}", ex.getStatusCode().value());
        String message = status == HttpStatus.UNAUTHORIZED
                ? "외부 서비스 인증에 실패했습니다. 설정을 확인해주세요."
                : "외부 서비스 호출 중 오류가 발생했습니다.";
        if (isA2a(request)) {
            return a2aError(status, INTERNAL_ERROR, message);
        }
        return ResponseEntity.status(status).body(new ErrorResponse(message));
    }

    /**
     * JSON 파싱 오류를 400 응답으로 변환한다.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<?> handleUnreadableMessage(HttpMessageNotReadableException ex, HttpServletRequest request) {
        logger.debug("Unreadable request body", ex);
        if (isA2a(request)) {
            return a2aError(HttpStatus.BAD_REQUEST, INVALID_REQUEST, "Invalid JSON-RPC request");
        }
        return ResponseEntity.badRequest().body(new ErrorResponse("Invalid request"));
    }

    /**
     * 현재 요청이 A2A 엔드포인트인지 판별한다.
     */
    private boolean isA2a(HttpServletRequest request) {
        return request != null && request.getRequestURI() != null && request.getRequestURI().startsWith("/a2a");
    }

    /**
     * JSON-RPC 에러 응답을 생성한다.
     */
    private ResponseEntity<JsonRpcResponse> a2aError(HttpStatus status, int code, String message) {
        return ResponseEntity.status(status).body(JsonRpcResponse.error(null, code, message));
    }

    /**
     * 외부 메시지를 사용자 응답용으로 안전하게 절단한다.
     */
    private String sanitizeMessage(String message) {
        if (message == null || message.isBlank()) {
            return "Invalid request";
        }
        return message.length() > 200 ? message.substring(0, 200) : message;
    }

    /**
     * 이름 기반 런타임 예외 매핑 규칙.
     */
    private record RuntimeExceptionRule(
            String simpleClassName,
            HttpStatus status,
            int a2aCode,
            String a2aMessage,
            String httpMessage,
            boolean useOriginalMessageForA2a
    ) {
        private String logPrefix() {
            return switch (simpleClassName) {
                case "A2ARoutingException" -> "Supervisor routing rejected";
                case "DownstreamA2AException" -> "Downstream A2A call failed";
                case "SupervisorChatProcessingException" -> "Supervisor compose failed";
                default -> "Unhandled runtime exception";
            };
        }
    }
}
