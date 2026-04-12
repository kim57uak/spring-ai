package com.example.springsupervisorai.exception;

public class SupervisorChatProcessingException extends RuntimeException {

    public SupervisorChatProcessingException(String message) {
        super(message);
    }

    public SupervisorChatProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}

