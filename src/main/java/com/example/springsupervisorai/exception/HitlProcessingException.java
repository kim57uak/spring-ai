package com.example.springsupervisorai.exception;

/**
 * HITL 처리 중 발생하는 예외를 나타냅니다.
 */
public class HitlProcessingException extends RuntimeException {
    public HitlProcessingException(String message) {
        super(message);
    }

    public HitlProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}