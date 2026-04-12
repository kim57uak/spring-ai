package com.example.springai.service.agent.execute;

import com.example.springai.config.McpProperties;
import com.example.springai.mcp.McpClient;
import com.example.springai.mcp.McpClientFactory;
import com.example.springai.mcp.ToolSchemaRegistry;
import com.example.springai.model.agent.PlanningContext;
import com.example.springai.model.agent.ToolExecutionResult;
import com.example.springai.model.agent.ToolPlan;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.IDN;
import java.net.InetAddress;
import java.net.Inet6Address;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 계획 결과를 바탕으로 MCP 도구를 실제 실행하고 결과를 표준 형태로 정규화한다.
 * 서버/도구 스코프 검증과 URL 안전성 검증을 함께 수행한다.
 */
@Component
public class McpToolExecutionService implements ToolExecutionService {

    private static final Logger logger = LoggerFactory.getLogger(McpToolExecutionService.class);
    private static final Set<String> BLOCKED_HOSTS = Set.of(
            "localhost",
            "metadata.google.internal",
            "metadata",
            "169.254.169.254",
            "100.100.100.200"
    );
    private static final Pattern SALE_PRODUCT_CODE_PATTERN = Pattern.compile("\\b[A-Z]{3}[A-Z0-9]{8,}\\b");

    private final McpClientFactory mcpClientFactory;
    private final McpProperties mcpProperties;
    private final ToolSchemaRegistry toolSchemaRegistry;
    private final ObjectMapper objectMapper;

    public McpToolExecutionService(
            McpClientFactory mcpClientFactory,
            McpProperties mcpProperties,
            ToolSchemaRegistry toolSchemaRegistry,
            ObjectMapper objectMapper
    ) {
        this.mcpClientFactory = mcpClientFactory;
        this.mcpProperties = mcpProperties;
        this.toolSchemaRegistry = toolSchemaRegistry;
        this.objectMapper = objectMapper;
    }

    /**
     * 도구 실행 메인 흐름.
     * <p>
     * 처리 순서:
     * - 스코프/서버/도구 허용 여부를 검증한다.
     * - 실행 파라미터를 구성한다.
     * - MCP 호출 결과를 텍스트 중심 페이로드로 정규화한다.
     */
    @Override
    public ToolExecutionResult execute(ToolPlan plan, PlanningContext context) {
        if (plan == null || !plan.toolRequired()) {
            return ToolExecutionResult.skipped();
        }

        String serverName = plan.serverName();
        if (!context.getScope().isServerAllowed(serverName)) {
            logger.warn("Blocked MCP tool execution by scope. server={}", serverName);
            return new ToolExecutionResult(serverName, "", "Server not allowed by scope", Map.of(), false, false);
        }
        McpProperties.ServerConfig serverConfig = mcpProperties.getServers().get(serverName);
        if (serverConfig == null) {
            logger.warn("Blocked MCP tool execution. Unknown server: {}", serverName);
            return new ToolExecutionResult(serverName, "", "Unknown MCP server", Map.of(), false, false);
        }

        McpClient client = mcpClientFactory.createClient(serverName);
        List<Map<String, Object>> availableTools = toolSchemaRegistry.loadTools(serverName, context.getScope());
        String resolvedTool = resolveToolName(client, serverConfig, plan.toolName(), serverName, availableTools);
        if (resolvedTool.isBlank()) {
            return new ToolExecutionResult(serverName, "", "No allowed/available MCP tool", Map.of(), false, false);
        }
        if (!context.getScope().isToolAllowed(serverName, resolvedTool)) {
            logger.warn("Blocked MCP tool execution by scope. server={}, tool={}", serverName, resolvedTool);
            return new ToolExecutionResult(serverName, resolvedTool, "Tool not allowed by scope", Map.of(), false, false);
        }

        try {
            Map<String, Object> params = plan.arguments() != null && !plan.arguments().isEmpty()
                    ? plan.arguments()
                    : buildParamsForTool(resolvedTool, context.getUserMessage(), availableTools);
            params = normalizeArgumentsForKnownTools(resolvedTool, params, context.getUserMessage());
            logger.info("Executing MCP tool server={}, tool={}, paramsKeys={}",
                    serverName, resolvedTool, params.keySet());
            String payload = client.callTool(resolvedTool, params);
            String normalizedPayload = normalizePayload(payload);
            if (isRetryableUpstreamFailure(normalizedPayload)) {
                logger.warn("MCP tool returned retryable upstream failure. server={}, tool={}, retry=1", serverName, resolvedTool);
                payload = client.callTool(resolvedTool, params);
                normalizedPayload = normalizePayload(payload);
            }
            boolean success = !isErrorPayload(normalizedPayload);
            logger.info("MCP tool result server={}, tool={}, payloadLength={}, preview={}",
                    serverName,
                    resolvedTool,
                    normalizedPayload.length(),
                    preview(normalizedPayload, 300));
            if (!success) {
                logger.warn("MCP tool responded with error payload. server={}, tool={}, preview={}",
                        serverName, resolvedTool, preview(normalizedPayload, 160));
            }
            return new ToolExecutionResult(serverName, resolvedTool, normalizedPayload, Map.copyOf(params), success, true);
        } catch (Exception e) {
            logger.warn("MCP execution failed for server={}, tool={}: {}", serverName, resolvedTool, e.getMessage());
            return new ToolExecutionResult(serverName, resolvedTool, "Tool call failed", Map.of(), false, true);
        }
    }

    private Map<String, Object> normalizeArgumentsForKnownTools(
            String toolName,
            Map<String, Object> originalParams,
            String userMessage
    ) {
        Map<String, Object> params = originalParams == null ? new LinkedHashMap<>() : new LinkedHashMap<>(originalParams);
        if (!"getSaleProductDetails".equals(toolName)) {
            return params;
        }

        Object requestObj = params.get("request");
        if (requestObj instanceof Map<?, ?> requestMapRaw) {
            Map<String, Object> request = new LinkedHashMap<>();
            requestMapRaw.forEach((k, v) -> request.put(String.valueOf(k), v));
            String saleProdCd = stringValue(request.get("saleProdCd"));
            if (saleProdCd.isBlank()) {
                saleProdCd = extractSaleProductCode(userMessage);
            }
            if (!saleProdCd.isBlank()) {
                request.put("saleProdCd", saleProdCd);
            }
            String guid = stringValue(request.get("guid"));
            if (guid.isBlank()) {
                request.put("guid", UUID.randomUUID().toString());
            }
            params.put("request", Map.copyOf(request));
            return params;
        }

        String saleProdCd = stringValue(params.get("saleProdCd"));
        if (saleProdCd.isBlank()) {
            saleProdCd = extractSaleProductCode(userMessage);
        }
        if (saleProdCd.isBlank()) {
            return params;
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("guid", UUID.randomUUID().toString());
        request.put("saleProdCd", saleProdCd);
        params.put("request", Map.copyOf(request));
        params.remove("saleProdCd");
        return params;
    }

    private String resolveToolName(
            McpClient client,
            McpProperties.ServerConfig config,
            String requestedTool,
            String serverName,
            List<Map<String, Object>> availableTools
    ) {
        // 우선순위: 요청된 도구 > allowTools > 서버별 선호 도구 > query 지원 도구 > 임의 사용 가능 도구
        if (requestedTool != null && !requestedTool.isBlank()) {
            if (isAllowedAndPresent(client, config, requestedTool)) {
                return requestedTool;
            }
            return "";
        }

        List<String> allowTools = config.getAllowTools();
        if (!allowTools.isEmpty()) {
            for (String tool : allowTools) {
                if (tool != null && !tool.isBlank()) {
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
        // 스키마 기반으로 최소 파라미터를 구성해 도구 호출 성공 확률을 높인다.
        Map<String, Object> schema = findToolSchema(toolName, availableTools);
        Set<String> fields = extractInputFields(schema);

        if (fields.contains("query")) {
            return Map.of("query", userMessage);
        }
        if (fields.contains("url")) {
            String extractedUrl = extractFirstUrl(userMessage);
            Optional<String> safeUrl = validateExternalUrl(extractedUrl);
            if (safeUrl.isPresent()) {
                return Map.of("url", safeUrl.get());
            }
            logger.warn("Blocked unsafe or missing URL for tool {}. Using query fallback only.", toolName);
            if (fields.contains("query")) {
                return Map.of("query", userMessage);
            }
            return Map.of();
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

    private Optional<String> validateExternalUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return Optional.empty();
        }
        URI uri;
        try {
            uri = new URI(rawUrl.trim());
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            return Optional.empty();
        }
        if (uri.getUserInfo() != null) {
            return Optional.empty();
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return Optional.empty();
        }
        String normalizedHost = IDN.toASCII(host.toLowerCase());
        if (isBlockedHost(normalizedHost)) {
            return Optional.empty();
        }
        if (!isPublicHost(normalizedHost)) {
            return Optional.empty();
        }
        return Optional.of(uri.toString());
    }

    private boolean isBlockedHost(String host) {
        if (BLOCKED_HOSTS.contains(host)) {
            return true;
        }
        return host.endsWith(".local")
                || host.endsWith(".internal")
                || host.endsWith(".localhost");
    }

    private boolean isPublicHost(String host) {
        try {
            // DNS 결과가 하나라도 사설/루프백이면 SSRF 위험으로 차단한다.
            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (addresses.length == 0) {
                return false;
            }
            for (InetAddress address : addresses) {
                if (!isPublicAddress(address)) {
                    return false;
                }
            }
            return true;
        } catch (UnknownHostException e) {
            return false;
        }
    }

    private boolean isPublicAddress(InetAddress address) {
        if (address instanceof Inet6Address inet6 && isUniqueLocalIpv6(inet6)) {
            return false;
        }
        return !(address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress());
    }

    private boolean isUniqueLocalIpv6(Inet6Address address) {
        byte first = address.getAddress()[0];
        int prefix = first & 0xFE;
        return prefix == 0xFC;
    }

    private String stringValue(Object raw) {
        return raw == null ? "" : String.valueOf(raw);
    }

    private String normalizePayload(String payload) {
        if (payload == null || payload.isBlank()) {
            return "";
        }
        try {
            // MCP content[].text 구조를 우선 병합하고, 파싱 실패 시 원문을 그대로 사용한다.
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

    private boolean isRetryableUpstreamFailure(String payload) {
        if (payload == null) {
            return false;
        }
        return payload.contains("[ERROR][REQUEST_FAILED]");
    }

    private boolean isErrorPayload(String payload) {
        if (payload == null || payload.isBlank()) {
            return true;
        }
        String normalized = payload.trim();
        return normalized.startsWith("[ERROR]")
                || normalized.contains("Tool call failed")
                || normalized.contains("request\" is null")
                || normalized.contains("Retries exhausted");
    }

    private String extractSaleProductCode(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return "";
        }
        Matcher matcher = SALE_PRODUCT_CODE_PATTERN.matcher(userMessage.toUpperCase());
        if (matcher.find()) {
            return matcher.group();
        }
        return "";
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
        if (!allowTools.isEmpty()) {
            return allowTools.contains(toolName);
        }
        return client.hasTool(toolName);
    }
}
