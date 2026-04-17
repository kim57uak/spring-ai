package com.example.springsupervisorai.service;

import com.example.springsupervisorai.model.SupervisorErrorCode;
import org.springframework.stereotype.Service;

import java.util.concurrent.CancellationException;

/**
 * Normalizes exception handling for supervisor orchestration and compose stages.
 */
@Service
public class SupervisorExceptionTranslator {

    /**
     * Structured failure translation result.
     *
     * @param errorCode normalized supervisor error code
     * @param userMessage user-facing error message
     * @param detail sanitized detail for persistence/logging
     */
    public record Failure(SupervisorErrorCode errorCode, String userMessage, String detail) {
    }

    /**
     * Translates a compose-stage exception.
     */
    public Failure composeFailure(Throwable error) {
        return new Failure(
                SupervisorErrorCode.COMPOSE_ERROR,
                "응답 합성 중 오류가 발생했습니다.",
                sanitize(error == null ? null : error.getMessage(), "Unexpected compose error")
        );
    }

    /**
     * Translates an orchestration-stage exception.
     */
    public Failure orchestrationFailure(Throwable error) {
        if (error instanceof CancellationException) {
            return new Failure(
                    SupervisorErrorCode.CANCELED,
                    "Supervisor 작업이 취소되었습니다.",
                    "Supervisor task canceled"
            );
        }
        return new Failure(
                SupervisorErrorCode.ORCHESTRATION_ERROR,
                "Supervisor 처리 중 오류가 발생했습니다.",
                sanitize(error == null ? null : error.getMessage(), "Unexpected supervisor error")
        );
    }

    /**
     * Sanitizes arbitrary values for logs and failure persistence.
     */
    public String sanitize(String message) {
        return sanitize(message, "Unexpected supervisor error");
    }

    private String sanitize(String message, String fallback) {
        if (message == null || message.isBlank()) {
            return fallback;
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
