package com.example.springsupervisorai.exception;

/**
 * Supervisor -> Downstream A2A 호출 실패 예외.
 */
public class DownstreamA2AException extends RuntimeException {

    /**
     * @param message 예외 요약 메시지
     */
    public DownstreamA2AException(String message) {
        super(message);
    }

    /**
     * @param message 예외 요약 메시지
     * @param cause   원인 예외
     */
    public DownstreamA2AException(String message, Throwable cause) {
        super(message, cause);
    }
}
