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

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final int INVALID_REQUEST = -32600;
    private static final int INVALID_PARAMS = -32602;
    private static final int INTERNAL_ERROR = -32603;

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

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<?> handleConstraintViolation(ConstraintViolationException ex, HttpServletRequest request) {
        logger.debug("Constraint violation", ex);
        if (isA2a(request)) {
            return a2aError(HttpStatus.BAD_REQUEST, INVALID_PARAMS, "입력값을 확인해주세요.");
        }
        return ResponseEntity.badRequest().body(new ErrorResponse("입력값을 확인해주세요."));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> handleIllegalArgument(IllegalArgumentException ex, HttpServletRequest request) {
        logger.debug("Illegal argument", ex);
        if (isA2a(request)) {
            return a2aError(HttpStatus.BAD_REQUEST, INVALID_PARAMS, "입력값을 확인해주세요.");
        }
        return ResponseEntity.badRequest().body(new ErrorResponse("입력값을 확인해주세요."));
    }

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

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<?> handleUnreadableMessage(HttpMessageNotReadableException ex, HttpServletRequest request) {
        logger.debug("Unreadable request body", ex);
        if (isA2a(request)) {
            return a2aError(HttpStatus.BAD_REQUEST, INVALID_REQUEST, "Invalid JSON-RPC request");
        }
        return ResponseEntity.badRequest().body(new ErrorResponse("Invalid request"));
    }

    private boolean isA2a(HttpServletRequest request) {
        return request != null && request.getRequestURI() != null && request.getRequestURI().startsWith("/a2a");
    }

    private ResponseEntity<JsonRpcResponse> a2aError(HttpStatus status, int code, String message) {
        return ResponseEntity.status(status).body(JsonRpcResponse.error(null, code, message));
    }
}
