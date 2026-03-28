package com.example.springai.mcp.exception;

/**
 * Exception thrown when MCP configuration validation fails.
 */
public class McpValidationException extends McpException {

    private final String validationType;

    public McpValidationException(String validationType, String message) {
        super(String.format("MCP validation failed (%s): %s", validationType, message));
        this.validationType = validationType;
    }

    public String getValidationType() {
        return validationType;
    }
}
