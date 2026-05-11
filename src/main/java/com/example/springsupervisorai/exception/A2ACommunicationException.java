package com.example.springsupervisorai.exception;

/**
 * A2A 통신 중 발생하는 예외를 나타냅니다.
 */
public class A2ACommunicationException extends RuntimeException {
    public A2ACommunicationException(String message) {
        super(message);
    }

    public A2ACommunicationException(String message, Throwable cause) {
        super(message, cause);
    }
}