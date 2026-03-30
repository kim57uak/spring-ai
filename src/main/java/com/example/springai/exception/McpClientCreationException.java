package com.example.springai.exception;

/**
 * Exception thrown when MCP client creation fails.
 */
public class McpClientCreationException extends McpException {

    private final String serverName;

    public McpClientCreationException(String serverName, String message) {
        super(String.format("Failed to create MCP client for server '%s': %s", serverName, message));
        this.serverName = serverName;
    }

    public McpClientCreationException(String serverName, String message, Throwable cause) {
        super(String.format("Failed to create MCP client for server '%s': %s", serverName, message), cause);
        this.serverName = serverName;
    }

    public String getServerName() {
        return serverName;
    }
}
