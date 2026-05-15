package com.example.springai.mcp;

import com.example.springai.config.McpProperties;
import com.example.springai.exception.McpToolCallException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * MCP client implementation wrapping official McpAsyncClient.
 */
public class SpringAiMcpClient implements McpClient {

    private static final Logger logger = LoggerFactory.getLogger(SpringAiMcpClient.class);
    
    private final McpAsyncClient client;
    private final ObjectMapper objectMapper;
    private final String serverName;
    private final Duration timeout;

    public SpringAiMcpClient(McpAsyncClient client, ObjectMapper objectMapper, String serverName, int timeoutMs) {
        this.client = client;
        this.objectMapper = objectMapper;
        this.serverName = serverName;
        this.timeout = Duration.ofMillis(timeoutMs);
    }

    @Override
    public String callTool(String toolName, Map<String, Object> params) {
        try {
            logger.debug("Calling MCP tool: server={}, tool={}, params={}", serverName, toolName, params);
            McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest(toolName, params))
                    .block(timeout);
            
            if (result == null) {
                throw new McpToolCallException(toolName, "No response from MCP server");
            }
            
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            logger.error("MCP tool call failed: server={}, tool={}", serverName, toolName, e);
            throw new McpToolCallException(toolName, e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        // Managed by McpClientSessionManager
    }

    @Override
    public boolean hasTool(String toolName) {
        return listTools().stream()
                .anyMatch(t -> toolName.equals(t.get("name")));
    }

    @Override
    public List<Map<String, Object>> listTools() {
        try {
            McpSchema.ListToolsResult result = client.listTools(null).block(timeout);
            if (result == null || result.tools() == null) {
                return Collections.emptyList();
            }
            
            return result.tools().stream()
                    .map(t -> objectMapper.convertValue(t, new TypeReference<Map<String, Object>>() {}))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            logger.warn("Failed to list MCP tools for server={}", serverName, e);
            return Collections.emptyList();
        }
    }
}
