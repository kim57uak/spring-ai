package com.example.springsupervisorai.exception;

public class DownstreamA2AException extends RuntimeException {

    public DownstreamA2AException(String message) {
        super(message);
    }

    public DownstreamA2AException(String message, Throwable cause) {
        super(message, cause);
    }
}

