package com.example.springai.service.chat.tool;

import com.example.springai.config.McpProperties;
import com.example.springai.mcp.McpClient;
import com.example.springai.mcp.McpClientFactory;
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

/**
 * MCP 도구 스키마를 Spring AI ToolCallback으로 변환해 제공하는 컴포넌트.
 * <p>
 * 도구 목록 조회 결과를 일정 시간 캐시해 콜백 재생성 비용을 줄인다.
 */
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

    /**
     * 도구 콜백 배열을 캐시 기반으로 반환한다.
     * <p>
     * 캐시 만료 전에는 기존 콜백을 재사용하고, 만료 시 동기화 구간에서 재구성한다.
     */
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

    /**
     * 서버별 도구 스키마를 순회해 실행 가능한 콜백 목록을 생성한다.
     * <p>
     * allow-tools 설정이 있으면 화이트리스트 기반으로 필터링한다.
     */
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

    /**
     * 단일 MCP 도구를 Spring AI FunctionToolCallback으로 변환한다.
     * <p>
     * callback 이름은 `server__tool` 규칙을 사용한다.
     */
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
        // 외부 구현체의 키 타입 차이를 제거하기 위해 String 키 맵으로 정규화한다.
        McpClient client = mcpClientFactory.createClient(serverName);
        List<Map<String, Object>> list = client.listTools();
        List<Map<String, Object>> tools = new ArrayList<>();
        for (Map<String, Object> map : list) {
            Map<String, Object> converted = new HashMap<>();
            map.forEach((k, v) -> converted.put(String.valueOf(k), v));
            tools.add(converted);
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
