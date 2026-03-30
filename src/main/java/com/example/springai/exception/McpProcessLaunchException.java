package com.example.springai.exception;

/**
 * Exception thrown when MCP process launch fails.
 */
public class McpProcessLaunchException extends McpException {

    private final String serverName;

    public McpProcessLaunchException(String serverName, String message) {
        super(String.format("Failed to launch MCP process for server '%s': %s", serverName, message));
        this.serverName = serverName;
    }

    public McpProcessLaunchException(String serverName, String message, Throwable cause) {
        super(String.format("Failed to launch MCP process for server '%s': %s", serverName, message), cause);
        this.serverName = serverName;
    }

    public String getServerName() {
        return serverName;
    }
}
