package com.example.springsupervisorai.exception;

/**
 * Supervisor의 planning/compose 단계 LLM 처리 실패 예외.
 */
public class SupervisorChatProcessingException extends RuntimeException {

    /**
     * @param message 예외 요약 메시지
     */
    public SupervisorChatProcessingException(String message) {
        super(message);
    }

    /**
     * @param message 예외 요약 메시지
     * @param cause   원인 예외
     */
    public SupervisorChatProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}
