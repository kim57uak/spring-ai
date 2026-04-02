package com.example.springai.service.chat.tool;

import com.example.springai.config.McpProperties;
import com.example.springai.mcp.McpClient;
import com.example.springai.mcp.McpClientFactory;
import com.example.springai.mcp.StdioMcpClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class McpToolCallbackProvider implements ToolCallbackProvider {

    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    private final McpProperties mcpProperties;
    private final McpClientFactory mcpClientFactory;
    private final ObjectMapper objectMapper;
    private volatile CachedCallbacks cache;

    public McpToolCallbackProvider(
            McpProperties mcpProperties,
            McpClientFactory mcpClientFactory,
            ObjectMapper objectMapper
    ) {
        this.mcpProperties = mcpProperties;
        this.mcpClientFactory = mcpClientFactory;
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolCallback[] getToolCallbacks() {
        long now = System.currentTimeMillis();
        CachedCallbacks current = cache;
        if (current != null && !current.isExpired(now)) {
            return current.callbacks();
        }
        synchronized (this) {
            current = cache;
            if (current != null && !current.isExpired(now)) {
                return current.callbacks();
            }
            ToolCallback[] callbacks = buildCallbacks();
            cache = new CachedCallbacks(callbacks, now + CACHE_TTL.toMillis());
            return callbacks;
        }
    }

    private ToolCallback[] buildCallbacks() {
        List<ToolCallback> callbacks = new ArrayList<>();
        mcpProperties.getServers().forEach((serverName, serverConfig) -> {
            Set<String> allowed = Set.copyOf(serverConfig.getAllowTools());
            List<Map<String, Object>> tools = loadTools(serverName);
            for (Map<String, Object> tool : tools) {
                String toolName = stringValue(tool.get("name"));
                if (toolName.isBlank()) {
                    continue;
                }
                if (!allowed.isEmpty() && !allowed.contains(toolName)) {
                    continue;
                }
                callbacks.add(buildCallback(serverName, toolName, tool));
            }
        });
        return callbacks.toArray(ToolCallback[]::new);
    }

    private ToolCallback buildCallback(String serverName, String toolName, Map<String, Object> toolSchema) {
        String callbackName = serverName + "__" + toolName;
        String description = "[server=%s] %s".formatted(serverName, stringValue(toolSchema.get("description")));
        String inputSchema = inputSchema(toolSchema);

        return FunctionToolCallback.<Map<String, Object>, String>builder(callbackName, arguments -> {
                    McpClient client = mcpClientFactory.createClient(serverName);
                    Map<String, Object> args = arguments == null ? Map.of() : Map.copyOf(arguments);
                    return client.callTool(toolName, args);
                })
                .description(description)
                .inputType(new ParameterizedTypeReference<Map<String, Object>>() {})
                .inputSchema(inputSchema)
                .build();
    }

    private String inputSchema(Map<String, Object> toolSchema) {
        Object schema = toolSchema.get("inputSchema");
        if (schema == null) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(schema);
        } catch (Exception e) {
            return "{}";
        }
    }

    private List<Map<String, Object>> loadTools(String serverName) {
        McpClient client = mcpClientFactory.createClient(serverName);
        if (!(client instanceof StdioMcpClient stdio)) {
            return List.of();
        }

        Object rawTools = stdio.getToolsSchema().get("tools");
        if (!(rawTools instanceof List<?> list)) {
            return List.of();
        }

        List<Map<String, Object>> tools = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> converted = new HashMap<>();
                map.forEach((k, v) -> converted.put(String.valueOf(k), v));
                tools.add(converted);
            }
        }
        return tools;
    }

    private String stringValue(Object raw) {
        return raw == null ? "" : String.valueOf(raw);
    }

    private record CachedCallbacks(ToolCallback[] callbacks, long expiresAtMs) {
        private boolean isExpired(long nowMs) {
            return nowMs >= expiresAtMs;
        }
    }
}
