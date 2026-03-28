package com.example.springai.mcp.exception;

/**
 * Exception thrown when MCP tool call fails.
 */
public class McpToolCallException extends McpException {

    private final String toolName;

    public McpToolCallException(String toolName, String message) {
        super(String.format("MCP tool call failed for '%s': %s", toolName, message));
        this.toolName = toolName;
    }

    public McpToolCallException(String toolName, String message, Throwable cause) {
        super(String.format("MCP tool call failed for '%s': %s", toolName, message), cause);
        this.toolName = toolName;
    }

    public String getToolName() {
        return toolName;
    }
}
