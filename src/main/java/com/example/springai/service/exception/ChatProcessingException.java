package com.example.springai.service.exception;

public class ChatProcessingException extends RuntimeException {

    public ChatProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}
