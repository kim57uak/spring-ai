package com.example.springai.mcp;

import com.example.springai.config.McpProperties;
import com.example.springai.model.agent.AgentScope;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * MCP 도구 스키마 조회 레지스트리.
 * reconnect-first 정책을 적용하고, 실패 시 캐시로 폴백한다.
 */
@Component
public class ToolSchemaRegistry {

    private static final Logger logger = LoggerFactory.getLogger(ToolSchemaRegistry.class);
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    private final McpClientFactory mcpClientFactory;
    private final McpProperties mcpProperties;
    private final ConcurrentMap<String, CachedTools> cache = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CachedTools> serverCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Boolean> refreshInFlight = new ConcurrentHashMap<>();
    private final ExecutorService schemaRefreshExecutor = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "mcp-schema-refresh");
        thread.setDaemon(true);
        return thread;
    });

    public ToolSchemaRegistry(McpClientFactory mcpClientFactory, McpProperties mcpProperties) {
        this.mcpClientFactory = mcpClientFactory;
        this.mcpProperties = mcpProperties;
    }

    @PostConstruct
    public void prewarmSchemas() {
        mcpProperties.getServers().keySet()
                .forEach(serverName -> scheduleRefresh(serverName, AgentScope.unrestricted()));
    }

    @PreDestroy
    public void shutdown() {
        schemaRefreshExecutor.shutdownNow();
    }

    /**
     * 서버 도구 목록 조회 진입점.
     * <p>
     * 조회 순서:
     * - scope-cache를 확인한다.
     * - server-cache를 확인하고 필요 시 백그라운드 갱신을 예약한다.
     * - 캐시 미스 시 원격 갱신을 비동기로 예약한다.
     * - 즉시 반환 가능한 데이터가 없으면 정적 allow-tools 설정으로 폴백한다.
     */
    public List<Map<String, Object>> loadTools(String serverName, AgentScope scope) {
        AgentScope safeScope = scope == null ? AgentScope.unrestricted() : scope;
        McpProperties.ServerConfig config = mcpProperties.getServers().get(serverName);
        if (config == null) {
            logger.warn("MCP schema lookup skipped: unknown server={}", serverName);
            return List.of();
        }

        logger.info(
                "MCP schema lookup start server={} transport={} scopeRestricted={}",
                serverName,
                config.getTransport(),
                !safeScope.isUnrestricted()
        );

        String cacheKey = buildCacheKey(serverName, safeScope);
        String serverCacheKey = buildServerCacheKey(serverName);
        CachedTools current = cache.get(cacheKey);
        CachedTools serverLevel = serverCache.get(serverCacheKey);
        long now = System.currentTimeMillis();

        if (current != null && !current.isExpired(now)) {
            logger.info("MCP schema lookup hit server={} source=scope-cache count={}", serverName, current.tools().size());
            return current.tools();
        }

        if (serverLevel != null && !serverLevel.isExpired(now)) {
            cache.put(cacheKey, serverLevel);
            logger.info("MCP schema lookup hit server={} source=server-cache count={}", serverName, serverLevel.tools().size());
            scheduleRefresh(serverName, safeScope);
            return serverLevel.tools();
        }

        scheduleRefresh(serverName, safeScope);

        if (current != null) {
            logger.info("MCP schema lookup fallback server={} source=stale-scope-cache count={}", serverName, current.tools().size());
            return current.tools();
        }
        if (serverLevel != null) {
            logger.info("MCP schema lookup fallback server={} source=stale-server-cache count={}", serverName, serverLevel.tools().size());
            return serverLevel.tools();
        }

        List<Map<String, Object>> staticTools = staticToolsFromConfig(config);
        if (!staticTools.isEmpty()) {
            logger.info(
                    "MCP schema lookup fallback server={} source=allow-tools tools={}",
                    serverName,
                    staticTools.stream().map(map -> String.valueOf(map.get("name"))).toList()
            );
        } else {
            logger.warn("MCP schema lookup fallback server={} source=empty", serverName);
        }
        return staticTools;
    }

    private List<Map<String, Object>> normalizeTools(List<Map<String, Object>> tools) {
        if (tools == null || tools.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (Map<String, Object> tool : tools) {
            if (tool == null || tool.isEmpty()) {
                continue;
            }
            Map<String, Object> copied = new LinkedHashMap<>(tool);
            normalized.add(Map.copyOf(copied));
        }
        return List.copyOf(normalized);
    }

    private List<Map<String, Object>> staticToolsFromConfig(McpProperties.ServerConfig config) {
        List<Map<String, Object>> tools = new ArrayList<>();
        for (String toolName : config.getAllowTools()) {
            if (toolName == null || toolName.isBlank()) {
                continue;
            }
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", Map.of());
            schema.put("required", List.of());

            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("name", toolName);
            tool.put("description", "Allowed tool from configuration");
            tool.put("inputSchema", schema);
            tools.add(Map.copyOf(tool));
        }
        return List.copyOf(tools);
    }

    private String buildCacheKey(String serverName, AgentScope scope) {
        McpProperties.ServerConfig config = mcpProperties.getServers().get(serverName);
        if (config == null) {
            return "unknown|" + serverName;
        }
        // 전송 방식/엔드포인트/허용도구/스코프 제약이 바뀌면 별도 캐시 엔트리로 분리한다.
        String transport = config.getTransport().toLowerCase();
        String endpointOrCommandSignature = "sse".equals(transport)
                ? (config.getHost() + config.getEndpoint())
                : (String.valueOf(config.getCommand()) + ":" + String.join(",", config.getArgs()));
        String toolSetHash = hashOf(String.join(",", config.getAllowTools()));
        String scopeHash = hashOf(scope.allowedServers().stream().sorted().collect(Collectors.joining(",")) + "|" +
                scope.allowedToolsByServer().entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .map(entry -> entry.getKey() + ":" + entry.getValue().stream().sorted().collect(Collectors.joining(",")))
                        .collect(Collectors.joining("|")));
        return transport + "|" + serverName + "|" + endpointOrCommandSignature + "|" + toolSetHash + "|" + scopeHash;
    }

    private String buildServerCacheKey(String serverName) {
        McpProperties.ServerConfig config = mcpProperties.getServers().get(serverName);
        if (config == null) {
            return "unknown|" + serverName;
        }
        String transport = config.getTransport().toLowerCase();
        String endpointOrCommandSignature = "sse".equals(transport)
                ? (config.getHost() + config.getEndpoint())
                : (String.valueOf(config.getCommand()) + ":" + String.join(",", config.getArgs()));
        String toolSetHash = hashOf(String.join(",", config.getAllowTools()));
        return transport + "|" + serverName + "|" + endpointOrCommandSignature + "|" + toolSetHash;
    }

    /**
     * 동일 키에 대한 중복 원격 조회를 방지하면서 비동기 갱신 작업을 등록한다.
     * <p>
     * refreshInFlight 맵으로 동시성 중복 실행을 차단한다.
     */
    private void scheduleRefresh(String serverName, AgentScope scope) {
        String taskKey = buildCacheKey(serverName, scope);
        if (refreshInFlight.putIfAbsent(taskKey, Boolean.TRUE) != null) {
            return;
        }
        schemaRefreshExecutor.submit(() -> {
            try {
                refreshFromRemote(serverName, scope);
            } finally {
                refreshInFlight.remove(taskKey);
            }
        });
    }

    /**
     * MCP 원격 listTools 결과를 받아 캐시에 반영한다.
     * <p>
     * 성공 시 scope/server 캐시를 함께 갱신하고, 실패 시 기존 캐시는 유지한다.
     */
    private void refreshFromRemote(String serverName, AgentScope scope) {
        McpProperties.ServerConfig config = mcpProperties.getServers().get(serverName);
        if (config == null) {
            return;
        }
        try {
            // 원격 조회 성공 시 scope/server 캐시를 동시에 갱신한다.
            McpClient client = mcpClientFactory.createClient(serverName);
            List<Map<String, Object>> remoteTools = normalizeTools(client.listTools());
            List<Map<String, Object>> filtered = filterByAllowTools(remoteTools, config.getAllowTools());
            long expiresAt = System.currentTimeMillis() + CACHE_TTL.toMillis();
            String toolSetHash = hashOf(filtered.stream().map(map -> String.valueOf(map.get("name"))).sorted().collect(Collectors.joining(",")));
            CachedTools cached = new CachedTools(filtered, expiresAt, toolSetHash);
            cache.put(buildCacheKey(serverName, scope), cached);
            serverCache.put(buildServerCacheKey(serverName), cached);
            logger.info(
                    "MCP schema lookup success server={} source=remote count={} cacheTtlMs={}",
                    serverName,
                    filtered.size(),
                    CACHE_TTL.toMillis()
            );
        } catch (Exception e) {
            logger.warn(
                    "MCP schema lookup failed server={} source=remote reason={} (async)",
                    serverName,
                    e.getMessage()
            );
        }
    }

    private List<Map<String, Object>> filterByAllowTools(List<Map<String, Object>> tools, List<String> allowTools) {
        if (allowTools == null || allowTools.isEmpty()) {
            return tools;
        }
        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Map<String, Object> tool : tools) {
            String name = String.valueOf(tool.get("name"));
            if (allowTools.contains(name)) {
                filtered.add(tool);
            }
        }
        return List.copyOf(filtered);
    }

    private String hashOf(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (Exception e) {
            return Integer.toHexString(raw.hashCode());
        }
    }

    private record CachedTools(List<Map<String, Object>> tools, long expiresAtMs, String toolSetHash) {
        private CachedTools {
            tools = List.copyOf(tools);
        }

        private boolean isExpired(long nowMs) {
            return nowMs >= expiresAtMs;
        }
    }
}
