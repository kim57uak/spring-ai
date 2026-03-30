package com.example.springai.exception;

/**
 * Base exception for all MCP-related errors.
 */
public class McpException extends RuntimeException {

    public McpException(String message) {
        super(message);
    }

    public McpException(String message, Throwable cause) {
        super(message, cause);
    }
}
