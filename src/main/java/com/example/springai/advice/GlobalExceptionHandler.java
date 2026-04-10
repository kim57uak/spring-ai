package com.example.springai.advice;

import com.example.springai.dto.ErrorResponse;
import com.example.springai.exception.ChatProcessingException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.reactive.function.client.WebClientResponseException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fieldError -> fieldError.getDefaultMessage())
                .orElse("Invalid request");
        return ResponseEntity.badRequest().body(new ErrorResponse(message));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex) {
        logger.debug("Constraint violation", ex);
        return ResponseEntity.badRequest().body(new ErrorResponse("입력값을 확인해주세요."));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        logger.debug("Illegal argument", ex);
        return ResponseEntity.badRequest().body(new ErrorResponse("입력값을 확인해주세요."));
    }

    @ExceptionHandler(ChatProcessingException.class)
    public ResponseEntity<ErrorResponse> handleChatProcessing(ChatProcessingException ex) {
        logger.warn("Chat processing failed", ex);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new ErrorResponse("요청 처리 중 문제가 발생했습니다. 잠시 후 다시 시도해주세요."));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleIllegalState(IllegalStateException ex) {
        logger.warn("Illegal state detected", ex);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorResponse("현재 요청을 처리할 수 없습니다. 잠시 후 다시 시도해주세요."));
    }

    @ExceptionHandler(WebClientResponseException.class)
    public ResponseEntity<ErrorResponse> handleWebClientResponse(WebClientResponseException ex) {
        HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
        logger.warn("Upstream API error status={}", ex.getStatusCode().value());
        String message = status == HttpStatus.UNAUTHORIZED
                ? "외부 서비스 인증에 실패했습니다. 설정을 확인해주세요."
                : "외부 서비스 호출 중 오류가 발생했습니다.";
        return ResponseEntity.status(status).body(new ErrorResponse(message));
    }
}
