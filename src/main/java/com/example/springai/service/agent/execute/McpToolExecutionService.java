package com.example.springai.service.agent.execute;

import com.example.springai.config.McpProperties;
import com.example.springai.mcp.McpClient;
import com.example.springai.mcp.McpClientFactory;
import com.example.springai.mcp.StdioMcpClient;
import com.example.springai.model.agent.PlanningContext;
import com.example.springai.model.agent.ToolExecutionResult;
import com.example.springai.model.agent.ToolPlan;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class McpToolExecutionService implements ToolExecutionService {

    private static final Logger logger = LoggerFactory.getLogger(McpToolExecutionService.class);

    private final McpClientFactory mcpClientFactory;
    private final McpProperties mcpProperties;
    private final ObjectMapper objectMapper;

    public McpToolExecutionService(
            McpClientFactory mcpClientFactory,
            McpProperties mcpProperties,
            ObjectMapper objectMapper
    ) {
        this.mcpClientFactory = mcpClientFactory;
        this.mcpProperties = mcpProperties;
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolExecutionResult execute(ToolPlan plan, PlanningContext context) {
        if (plan == null || !plan.toolRequired()) {
            return ToolExecutionResult.skipped();
        }

        String serverName = plan.serverName();
        McpProperties.ServerConfig serverConfig = mcpProperties.getServers().get(serverName);
        if (serverConfig == null) {
            logger.warn("Blocked MCP tool execution. Unknown server: {}", serverName);
            return new ToolExecutionResult(serverName, "", "Unknown MCP server", Map.of(), false, false);
        }

        McpClient client = mcpClientFactory.createClient(serverName);
        List<Map<String, Object>> availableTools = extractAvailableTools(client);
        String resolvedTool = resolveToolName(client, serverConfig, plan.toolName(), serverName, availableTools);
        if (resolvedTool.isBlank()) {
            return new ToolExecutionResult(serverName, "", "No allowed/available MCP tool", Map.of(), false, false);
        }

        try {
            Map<String, Object> params = plan.arguments() != null && !plan.arguments().isEmpty()
                    ? plan.arguments()
                    : buildParamsForTool(resolvedTool, context.getUserMessage(), availableTools);
            logger.info("Executing MCP tool server={}, tool={}, paramsKeys={}",
                    serverName, resolvedTool, params.keySet());
            String payload = client.callTool(resolvedTool, params);
            String normalizedPayload = normalizePayload(payload);
            logger.info("MCP tool result server={}, tool={}, payloadLength={}, preview={}",
                    serverName,
                    resolvedTool,
                    normalizedPayload.length(),
                    preview(normalizedPayload, 300));
            return new ToolExecutionResult(serverName, resolvedTool, normalizedPayload, Map.copyOf(params), true, true);
        } catch (Exception e) {
            logger.warn("MCP execution failed for server={}, tool={}: {}", serverName, resolvedTool, e.getMessage());
            return new ToolExecutionResult(serverName, resolvedTool, "Tool call failed", Map.of(), false, true);
        }
    }

    private String resolveToolName(
            McpClient client,
            McpProperties.ServerConfig config,
            String requestedTool,
            String serverName,
            List<Map<String, Object>> availableTools
    ) {
        if (requestedTool != null && !requestedTool.isBlank()) {
            if (isAllowedAndPresent(client, config, requestedTool)) {
                return requestedTool;
            }
            return "";
        }

        List<String> allowTools = config.getAllowTools();
        if (!allowTools.isEmpty()) {
            for (String tool : allowTools) {
                if (client.hasTool(tool)) {
                    return tool;
                }
            }
            return "";
        }

        for (String preferred : preferredToolNames(serverName)) {
            if (client.hasTool(preferred)) {
                return preferred;
            }
        }

        for (Map<String, Object> tool : availableTools) {
            String toolName = stringValue(tool.get("name"));
            if (!toolName.isBlank() && supportsQuery(tool) && client.hasTool(toolName)) {
                return toolName;
            }
        }

        for (Map<String, Object> tool : availableTools) {
            String toolName = stringValue(tool.get("name"));
            if (!toolName.isBlank() && client.hasTool(toolName)) {
                return toolName;
            }
        }

        return "";
    }

    private List<String> preferredToolNames(String serverName) {
        if ("search-mcp-server".equals(serverName)) {
            return List.of("search", "perplexitySearch");
        }
        if ("search-economy-index".equals(serverName)) {
            return List.of("search_domestic_ticker", "search_overseas_ticker", "search_crypto_ticker");
        }
        return List.of();
    }

    private Map<String, Object> buildParamsForTool(
            String toolName,
            String userMessage,
            List<Map<String, Object>> availableTools
    ) {
        Map<String, Object> schema = findToolSchema(toolName, availableTools);
        Set<String> fields = extractInputFields(schema);

        if (fields.contains("query")) {
            return Map.of("query", userMessage);
        }
        if (fields.contains("url")) {
            String extractedUrl = extractFirstUrl(userMessage);
            if (extractedUrl != null) {
                return Map.of("url", extractedUrl);
            }
            return Map.of("url", "https://www.google.com/search", "query", userMessage);
        }
        if (fields.contains("queries")) {
            return Map.of("queries", List.of(userMessage));
        }
        if (fields.contains("symbols")) {
            return Map.of("symbols", List.of(userMessage));
        }
        if (fields.contains("tickers")) {
            return Map.of("tickers", List.of(userMessage));
        }

        return new HashMap<>();
    }

    private Map<String, Object> findToolSchema(String toolName, List<Map<String, Object>> availableTools) {
        for (Map<String, Object> tool : availableTools) {
            if (toolName.equals(stringValue(tool.get("name")))) {
                return tool;
            }
        }
        return Map.of();
    }

    private Set<String> extractInputFields(Map<String, Object> toolSchema) {
        Object inputSchemaObj = toolSchema.get("inputSchema");
        if (!(inputSchemaObj instanceof Map<?, ?> inputSchema)) {
            return Set.of();
        }
        Object propertiesObj = inputSchema.get("properties");
        if (!(propertiesObj instanceof Map<?, ?> properties)) {
            return Set.of();
        }
        Set<String> fields = new HashSet<>();
        for (Object key : properties.keySet()) {
            fields.add(stringValue(key));
        }
        return fields;
    }

    private boolean supportsQuery(Map<String, Object> toolSchema) {
        return extractInputFields(toolSchema).contains("query");
    }

    private String extractFirstUrl(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String[] tokens = text.split("\\s+");
        for (String token : tokens) {
            String lowered = token.toLowerCase();
            if (lowered.startsWith("http://") || lowered.startsWith("https://")) {
                return token;
            }
        }
        return null;
    }

    private List<Map<String, Object>> extractAvailableTools(McpClient client) {
        if (!(client instanceof StdioMcpClient stdio)) {
            return List.of();
        }
        Object rawTools = stdio.getToolsSchema().get("tools");
        if (!(rawTools instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> tools = new ArrayList<>();
        for (Object tool : list) {
            if (tool instanceof Map<?, ?> rawMap) {
                Map<String, Object> converted = new HashMap<>();
                rawMap.forEach((k, v) -> converted.put(stringValue(k), v));
                tools.add(converted);
            }
        }
        return tools;
    }

    private String stringValue(Object raw) {
        return raw == null ? "" : String.valueOf(raw);
    }

    private String normalizePayload(String payload) {
        if (payload == null || payload.isBlank()) {
            return "";
        }
        try {
            JsonNode root = objectMapper.readTree(payload);
            JsonNode content = root.path("content");
            if (content.isArray() && !content.isEmpty()) {
                StringBuilder text = new StringBuilder();
                for (JsonNode item : content) {
                    String value = item.path("text").asText("");
                    if (!value.isBlank()) {
                        text.append(value).append("\n");
                    }
                }
                String merged = text.toString().trim();
                if (!merged.isBlank()) {
                    return merged;
                }
            }
            return payload;
        } catch (Exception ignored) {
            return payload;
        }
    }

    private String preview(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String oneLine = value.replace('\n', ' ').replace('\r', ' ');
        if (oneLine.length() <= maxLength) {
            return oneLine;
        }
        return oneLine.substring(0, maxLength) + "...";
    }

    private boolean isAllowedAndPresent(McpClient client, McpProperties.ServerConfig config, String toolName) {
        List<String> allowTools = config.getAllowTools();
        if (!allowTools.isEmpty() && !allowTools.contains(toolName)) {
            return false;
        }
        return client.hasTool(toolName);
    }
}
