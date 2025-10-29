package com.example.springai.mcp;

import java.util.Map;

public interface McpClient {
    String callTool(String toolName, Map<String, Object> params);
    void close();
    boolean hasTool(String toolName);
}