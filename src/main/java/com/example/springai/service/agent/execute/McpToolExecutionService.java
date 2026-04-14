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
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.net.IDN;
import java.net.InetAddress;
import java.net.Inet6Address;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
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
    private static final String MDC_GUID_KEY = "guid";
    private static final Set<String> BLOCKED_HOSTS = Set.of(
            "localhost",
            "metadata.google.internal",
            "metadata",
            "169.254.169.254",
            "100.100.100.200"
    );
    private static final int DEFAULT_QUERY_MAX_CALLS_PER_REQUEST = 4;
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

        String sessionId = context == null ? "" : stringValue(context.getSessionId());
        String serverName = plan.serverName();
        if (!context.getScope().isServerAllowed(serverName)) {
            logger.warn("Blocked MCP tool execution by scope. sessionId={}, server={}", sessionId, serverName);
            return new ToolExecutionResult(serverName, "", "Server not allowed by scope", Map.of(), false, false, false);
        }
        McpProperties.ServerConfig serverConfig = mcpProperties.getServers().get(serverName);
        if (serverConfig == null) {
            logger.warn("Blocked MCP tool execution. sessionId={}, unknownServer={}", sessionId, serverName);
            return new ToolExecutionResult(serverName, "", "Unknown MCP server", Map.of(), false, false, false);
        }

        McpClient client = mcpClientFactory.createClient(serverName);
        List<Map<String, Object>> availableTools = toolSchemaRegistry.loadTools(serverName, context.getScope());
        String resolvedTool = resolveToolName(client, serverConfig, plan.toolName(), serverName, availableTools);
        if (resolvedTool.isBlank()) {
            return new ToolExecutionResult(serverName, "", "No allowed/available MCP tool", Map.of(), false, false, false);
        }
        if (!context.getScope().isToolAllowed(serverName, resolvedTool)) {
            logger.warn("Blocked MCP tool execution by scope. sessionId={}, server={}, tool={}", sessionId, serverName, resolvedTool);
            return new ToolExecutionResult(serverName, resolvedTool, "Tool not allowed by scope", Map.of(), false, false, false);
        }
        ToolExecutionPolicy policy = resolvePolicy(serverName, serverConfig, resolvedTool);
        boolean terminalAfterExecution = policy.operation() == McpProperties.ToolOperation.MUTATION;

        try {
            Map<String, Object> params = plan.arguments() != null && !plan.arguments().isEmpty()
                    ? plan.arguments()
                    : buildParamsForTool(resolvedTool, context.getUserMessage(), availableTools);
            params = normalizeArgumentsForKnownTools(resolvedTool, params, context.getUserMessage());
            params = applyIdempotencyIfRequired(policy, params, context, serverName, resolvedTool);
            Map<String, Object> selectedToolSchema = findToolSchema(resolvedTool, availableTools);
            params = applyGuidIfPresent(params, selectedToolSchema);
            List<String> missingRequiredParams = detectMissingRequiredParams(params, selectedToolSchema);
            if (!missingRequiredParams.isEmpty()) {
                String missingPayload = buildMissingRequiredPayload(resolvedTool, missingRequiredParams, selectedToolSchema);
                logger.warn("MCP tool required params missing. sessionId={}, server={}, tool={}, missing={}",
                        sessionId, serverName, resolvedTool, missingRequiredParams);
                return new ToolExecutionResult(
                        serverName,
                        resolvedTool,
                        missingPayload,
                        Map.copyOf(params),
                        false,
                        true,
                        terminalAfterExecution
                );
            }
            int currentCallCount = context.getToolCallCount(serverName, resolvedTool);
            if (currentCallCount >= policy.maxCallsPerRequest()) {
                logger.warn("MCP tool call blocked by policy. sessionId={}, server={}, tool={}, operation={}, maxCallsPerRequest={}, currentCallCount={}",
                        sessionId, serverName, resolvedTool, policy.operation(), policy.maxCallsPerRequest(), currentCallCount);
                return new ToolExecutionResult(
                        serverName,
                        resolvedTool,
                        "[POLICY_SKIPPED][MAX_CALLS_PER_REQUEST] server=" + serverName + ", tool=" + resolvedTool + ", max=" + policy.maxCallsPerRequest(),
                        Map.copyOf(params),
                        true,
                        false,
                        terminalAfterExecution
                );
            }
            context.incrementToolCallCount(serverName, resolvedTool);
            logger.info("Executing MCP tool sessionId={}, server={}, tool={}, paramsKeys={}",
                    sessionId, serverName, resolvedTool, params.keySet());
            String payload = client.callTool(resolvedTool, params);
            String normalizedPayload = normalizePayload(payload);
            if (policy.retryable() && isRetryableUpstreamFailure(normalizedPayload)) {
                logger.warn("MCP tool returned retryable upstream failure. sessionId={}, server={}, tool={}, retry=1",
                        sessionId, serverName, resolvedTool);
                payload = client.callTool(resolvedTool, params);
                normalizedPayload = normalizePayload(payload);
            }
            boolean success = !isErrorPayload(normalizedPayload);
            logger.info("MCP tool result sessionId={}, server={}, tool={}, payloadLength={}, preview={}",
                    sessionId,
                    serverName,
                    resolvedTool,
                    normalizedPayload.length(),
                    preview(normalizedPayload, 300));
            if (!success) {
                logger.warn("MCP tool responded with error payload. sessionId={}, server={}, tool={}, payloadPreview={}",
                        sessionId, serverName, resolvedTool, preview(normalizedPayload, 200));
            }
            return new ToolExecutionResult(serverName, resolvedTool, normalizedPayload, Map.copyOf(params), success, true, terminalAfterExecution);
        } catch (Exception e) {
            logger.warn("MCP execution failed. sessionId={}, server={}, tool={}, error={}",
                    sessionId, serverName, resolvedTool, e.getMessage(), e);
            return new ToolExecutionResult(serverName, resolvedTool, "Tool call failed", Map.of(), false, true, terminalAfterExecution);
        }
    }

    private ToolExecutionPolicy resolvePolicy(
            String serverName,
            McpProperties.ServerConfig serverConfig,
            String toolName
    ) {
        McpProperties.ToolPolicy configured = findToolPolicy(serverConfig, toolName);
        if (configured != null) {
            ToolExecutionPolicy resolved = new ToolExecutionPolicy(
                    configured.getOperation(),
                    configured.isRetryable(),
                    Math.max(1, configured.getMaxCallsPerRequest()),
                    configured.isRequireIdempotencyKey()
            );
            logger.info("MCP tool policy resolved. server={}, tool={}, operation={}, retryable={}, maxCallsPerRequest={}, requireIdempotencyKey={}",
                    serverName, toolName, resolved.operation(), resolved.retryable(), resolved.maxCallsPerRequest(), resolved.requireIdempotencyKey());
            return resolved;
        }
        logger.warn("MCP tool policy not configured. Applying default query policy. server={}, tool={}", serverName, toolName);
        return new ToolExecutionPolicy(
                McpProperties.ToolOperation.QUERY,
                true,
                DEFAULT_QUERY_MAX_CALLS_PER_REQUEST,
                false
        );
    }

    private McpProperties.ToolPolicy findToolPolicy(McpProperties.ServerConfig serverConfig, String toolName) {
        if (serverConfig == null || serverConfig.getToolPolicies() == null || serverConfig.getToolPolicies().isEmpty()) {
            return null;
        }
        Map<String, McpProperties.ToolPolicy> policies = serverConfig.getToolPolicies();
        McpProperties.ToolPolicy exact = policies.get(toolName);
        if (exact != null) {
            return exact;
        }
        String normalized = safeLookupKey(toolName);
        for (Map.Entry<String, McpProperties.ToolPolicy> entry : policies.entrySet()) {
            if (safeLookupKey(entry.getKey()).equals(normalized)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private String safeLookupKey(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private Map<String, Object> applyIdempotencyIfRequired(
            ToolExecutionPolicy policy,
            Map<String, Object> originalParams,
            PlanningContext context,
            String serverName,
            String toolName
    ) {
        if (!policy.requireIdempotencyKey()) {
            return originalParams;
        }
        Map<String, Object> params = originalParams == null ? new LinkedHashMap<>() : new LinkedHashMap<>(originalParams);
        String existing = stringValue(params.get("idempotencyKey"));
        if (!existing.isBlank()) {
            return params;
        }
        params.put("idempotencyKey", buildIdempotencyKey(context, serverName, toolName, params));
        return params;
    }

    private String buildIdempotencyKey(
            PlanningContext context,
            String serverName,
            String toolName,
            Map<String, Object> params
    ) {
        String sessionId = context == null ? "" : stringValue(context.getSessionId());
        String fingerprint = paramsFingerprint(params);
        return "mcp:" + sessionId + ":" + safeToken(serverName) + ":" + safeToken(toolName) + ":" + fingerprint;
    }

    private String safeToken(String value) {
        if (value == null || value.isBlank()) {
            return "na";
        }
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
    }

    private String paramsFingerprint(Map<String, Object> params) {
        Map<String, Object> safe = params == null ? Map.of() : params;
        try {
            String canonicalJson = objectMapper.writeValueAsString(canonicalizeValue(safe));
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonicalJson.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            return UUID.randomUUID().toString();
        }
    }

    @SuppressWarnings("unchecked")
    private Object canonicalizeValue(Object raw) {
        if (raw instanceof Map<?, ?> map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            map.forEach((key, value) -> sorted.put(String.valueOf(key), canonicalizeValue(value)));
            return sorted;
        }
        if (raw instanceof List<?> list) {
            List<Object> normalized = new ArrayList<>(list.size());
            for (Object item : list) {
                normalized.add(canonicalizeValue(item));
            }
            return normalized;
        }
        return raw;
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
                request.put("guid", resolveGuidWithMdc());
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
        request.put("guid", resolveGuidWithMdc());
        request.put("saleProdCd", saleProdCd);
        params.put("request", Map.copyOf(request));
        params.remove("saleProdCd");
        return params;
    }

    /**
     * 입력 스키마에 guid 필드가 정의되어 있고 값이 비어 있으면 MDC 기반 guid를 주입한다.
     * <p>
     * 동작 원칙:
     * - root object의 guid는 직접 주입한다.
     * - 중첩 object는 스키마에 guid 경로가 있으면 필요 시 생성해 주입한다.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> applyGuidIfPresent(Map<String, Object> originalParams, Map<String, Object> toolSchema) {
        Map<String, Object> params = originalParams == null ? new LinkedHashMap<>() : new LinkedHashMap<>(originalParams);
        if (toolSchema == null || toolSchema.isEmpty()) {
            return params;
        }
        Object inputSchemaObj = toolSchema.get("inputSchema");
        if (!(inputSchemaObj instanceof Map<?, ?> inputSchema)) {
            return params;
        }
        applyGuidToObject(params, inputSchema);
        return params;
    }

    @SuppressWarnings("unchecked")
    private void applyGuidToObject(Map<String, Object> target, Map<?, ?> schema) {
        if (target == null || schema == null || schema.isEmpty()) {
            return;
        }
        Object propertiesObj = schema.get("properties");
        if (!(propertiesObj instanceof Map<?, ?> properties) || properties.isEmpty()) {
            return;
        }

        String guidField = findGuidFieldName(properties);
        if (!guidField.isBlank()) {
            String guidKey = findExistingKeyIgnoreCase(target, guidField);
            if (guidKey.isBlank()) {
                guidKey = guidField;
            }
            String current = stringValue(target.get(guidKey));
            if (current.isBlank()) {
                target.put(guidKey, resolveGuidWithMdc());
            }
        }

        for (Map.Entry<?, ?> entry : properties.entrySet()) {
            String fieldName = stringValue(entry.getKey());
            if (fieldName.isBlank()) {
                continue;
            }
            Object childSchemaObj = entry.getValue();
            if (!(childSchemaObj instanceof Map<?, ?> childSchema)) {
                continue;
            }
            String targetFieldKey = findExistingKeyIgnoreCase(target, fieldName);
            if (targetFieldKey.isBlank()) {
                targetFieldKey = fieldName;
            }
            Object childValue = target.get(targetFieldKey);

            if (childValue instanceof Map<?, ?> childMapRaw) {
                Map<String, Object> childMap = new LinkedHashMap<>();
                childMapRaw.forEach((k, v) -> childMap.put(String.valueOf(k), v));
                applyGuidToObject(childMap, childSchema);
                target.put(targetFieldKey, Map.copyOf(childMap));
                continue;
            }

            // 중첩 object가 비어 있어도 스키마에 guid 경로가 있으면 객체를 생성해 guid를 채운다.
            if (isObjectSchema(childSchema) && schemaContainsGuid(childSchema)) {
                Map<String, Object> created = new LinkedHashMap<>();
                applyGuidToObject(created, childSchema);
                if (!created.isEmpty()) {
                    target.put(targetFieldKey, Map.copyOf(created));
                }
            }

            if (childValue instanceof Collection<?> childCollection && isArraySchema(childSchema)) {
                List<Object> rewritten = new ArrayList<>(childCollection.size());
                Map<?, ?> itemSchema = resolveArrayItemSchema(childSchema);
                for (Object item : childCollection) {
                    if (item instanceof Map<?, ?> itemMapRaw && itemSchema != null) {
                        Map<String, Object> itemMap = new LinkedHashMap<>();
                        itemMapRaw.forEach((k, v) -> itemMap.put(String.valueOf(k), v));
                        applyGuidToObject(itemMap, itemSchema);
                        rewritten.add(Map.copyOf(itemMap));
                        continue;
                    }
                    rewritten.add(item);
                }
                target.put(targetFieldKey, List.copyOf(rewritten));
            }
        }
    }

    private boolean isObjectSchema(Map<?, ?> schema) {
        Object propertiesObj = schema.get("properties");
        return propertiesObj instanceof Map<?, ?>;
    }

    private boolean isArraySchema(Map<?, ?> schema) {
        Object type = schema.get("type");
        return "array".equalsIgnoreCase(stringValue(type));
    }

    private Map<?, ?> resolveArrayItemSchema(Map<?, ?> arraySchema) {
        Object itemsObj = arraySchema.get("items");
        if (itemsObj instanceof Map<?, ?> itemSchema) {
            return itemSchema;
        }
        return null;
    }

    private boolean schemaContainsGuid(Map<?, ?> schema) {
        if (schema == null || schema.isEmpty()) {
            return false;
        }
        Object propertiesObj = schema.get("properties");
        if (propertiesObj instanceof Map<?, ?> properties && !findGuidFieldName(properties).isBlank()) {
            return true;
        }
        if (propertiesObj instanceof Map<?, ?> properties) {
            for (Object value : properties.values()) {
                if (value instanceof Map<?, ?> childSchema && schemaContainsGuid(childSchema)) {
                    return true;
                }
            }
        }
        Object itemsObj = schema.get("items");
        if (itemsObj instanceof Map<?, ?> itemSchema) {
            return schemaContainsGuid(itemSchema);
        }
        return false;
    }

    private String findExistingKeyIgnoreCase(Map<String, Object> source, String key) {
        if (source == null || source.isEmpty() || key == null || key.isBlank()) {
            return "";
        }
        for (String existing : source.keySet()) {
            if (existing != null && existing.equalsIgnoreCase(key)) {
                return existing;
            }
        }
        return "";
    }

    private String findGuidFieldName(Map<?, ?> properties) {
        if (properties == null || properties.isEmpty()) {
            return "";
        }
        for (Object key : properties.keySet()) {
            String field = stringValue(key).trim();
            if ("guid".equalsIgnoreCase(field)) {
                return field;
            }
        }
        return "";
    }

    /**
     * 요청 컨텍스트(MDC)에 guid가 있으면 재사용하고, 없으면 생성해 MDC에 저장한다.
     */
    private String resolveGuidWithMdc() {
        String existing = stringValue(MDC.get(MDC_GUID_KEY)).trim();
        if (!existing.isBlank()) {
            return existing;
        }
        String generated = UUID.randomUUID().toString();
        MDC.put(MDC_GUID_KEY, generated);
        return generated;
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

    @SuppressWarnings("unchecked")
    private List<String> detectMissingRequiredParams(
            Map<String, Object> params,
            Map<String, Object> toolSchema
    ) {
        if (toolSchema == null || toolSchema.isEmpty()) {
            return List.of();
        }
        Object inputSchemaObj = toolSchema.get("inputSchema");
        if (!(inputSchemaObj instanceof Map<?, ?> inputSchema)) {
            return List.of();
        }

        LinkedHashSet<String> missing = new LinkedHashSet<>();
        Object root = params == null ? Map.of() : params;
        collectMissingRequired(inputSchema, root, "", true, missing);
        return List.copyOf(missing);
    }

    @SuppressWarnings("unchecked")
    private void collectMissingRequired(
            Map<?, ?> schema,
            Object currentValue,
            String prefix,
            boolean enforceCurrentObject,
            LinkedHashSet<String> missing
    ) {
        if (schema == null || missing == null) {
            return;
        }

        Map<?, ?> currentMap = currentValue instanceof Map<?, ?> map ? map : Map.of();
        Object requiredObj = schema.get("required");
        Object propertiesObj = schema.get("properties");
        if (!(propertiesObj instanceof Map<?, ?> properties)) {
            return;
        }

        // 1) 현재 object의 required 필드는 무조건 검사(단, 기술 필드는 제외)
        if (enforceCurrentObject && requiredObj instanceof Collection<?> requiredFields) {
            for (Object rawField : requiredFields) {
                String field = stringValue(rawField).trim();
                if (field.isBlank()) {
                    continue;
                }
                String path = prefix.isBlank() ? field : prefix + "." + field;
                if (isTechnicalRequiredField(path)) {
                    continue;
                }
                if (!currentMap.containsKey(field) || isBlankValue(currentMap.get(field))) {
                    missing.add(path);
                    continue;
                }
                Object childSchemaObj = properties.get(field);
                if (childSchemaObj instanceof Map<?, ?> childSchema) {
                    collectMissingRequired(childSchema, currentMap.get(field), path, true, missing);
                }
            }
        }

        // 2) optional 객체가 실제로 전달된 경우에만 내부 required 검사
        for (Map.Entry<?, ?> entry : properties.entrySet()) {
            String field = stringValue(entry.getKey()).trim();
            if (field.isBlank()) {
                continue;
            }
            if (!currentMap.containsKey(field)) {
                continue;
            }
            Object childValue = currentMap.get(field);
            Object childSchemaObj = entry.getValue();
            if (!(childSchemaObj instanceof Map<?, ?> childSchema)) {
                continue;
            }
            String childPath = prefix.isBlank() ? field : prefix + "." + field;
            boolean childIsObjectLike = childValue instanceof Map<?, ?>;
            if (childIsObjectLike) {
                collectMissingRequired(childSchema, childValue, childPath, true, missing);
            }
        }
    }

    private boolean isBlankValue(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof String text) {
            return text.trim().isBlank();
        }
        if (value instanceof Collection<?> collection) {
            return collection.isEmpty();
        }
        if (value instanceof Map<?, ?> map) {
            return map.isEmpty();
        }
        return false;
    }

    private boolean isTechnicalRequiredField(String path) {
        String normalized = path == null ? "" : path.trim().toLowerCase(Locale.ROOT);
        return normalized.endsWith("guid")
                || normalized.endsWith("idempotencykey");
    }

    private String buildMissingRequiredPayload(String toolName, List<String> missingPaths, Map<String, Object> toolSchema) {
        LinkedHashSet<String> labels = new LinkedHashSet<>();
        for (String path : missingPaths) {
            labels.add(toParamLabel(path, toolSchema));
        }
        String requested = String.join(", ", labels);
        return "[MISSING_REQUIRED_PARAMS] tool=" + toolName
                + ", missing=" + missingPaths
                + ", message=필수 입력값이 부족합니다. 다음 정보를 입력해 주세요: " + requested;
    }

    private String toParamLabel(String path, Map<String, Object> toolSchema) {
        String fromSchema = resolveSchemaLabel(path, toolSchema);
        if (!fromSchema.isBlank()) {
            return fromSchema;
        }
        String key = path == null ? "" : path.substring(path.lastIndexOf('.') + 1).trim();
        return key.isBlank() ? "필수 입력값" : key;
    }

    @SuppressWarnings("unchecked")
    private String resolveSchemaLabel(String path, Map<String, Object> toolSchema) {
        if (path == null || path.isBlank() || toolSchema == null || toolSchema.isEmpty()) {
            return "";
        }
        Object inputSchemaObj = toolSchema.get("inputSchema");
        if (!(inputSchemaObj instanceof Map<?, ?> inputSchema)) {
            return "";
        }
        Map<?, ?> current = inputSchema;
        Map<?, ?> propertySchema = null;
        String[] parts = path.split("\\.");
        for (String part : parts) {
            Object propertiesObj = current.get("properties");
            if (!(propertiesObj instanceof Map<?, ?> properties)) {
                return "";
            }
            Object child = properties.get(part);
            if (!(child instanceof Map<?, ?> childSchema)) {
                return "";
            }
            propertySchema = childSchema;
            current = childSchema;
        }
        if (propertySchema == null) {
            return "";
        }
        String title = stringValue(propertySchema.get("title")).trim();
        if (!title.isBlank()) {
            return title;
        }
        String description = stringValue(propertySchema.get("description")).trim();
        if (!description.isBlank()) {
            return description;
        }
        return "";
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

    private record ToolExecutionPolicy(
            McpProperties.ToolOperation operation,
            boolean retryable,
            int maxCallsPerRequest,
            boolean requireIdempotencyKey
    ) {
    }
}
