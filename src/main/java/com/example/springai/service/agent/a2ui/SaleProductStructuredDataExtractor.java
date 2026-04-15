package com.example.springai.service.agent.a2ui;

import com.example.springai.model.agent.AgentScopeName;
import com.example.springai.model.agent.PlanningContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Component
public class SaleProductStructuredDataExtractor implements ScopedAgentStructuredDataExtractor {

    private static final Logger logger = LoggerFactory.getLogger(SaleProductStructuredDataExtractor.class);
    private final ObjectMapper objectMapper;

    public SaleProductStructuredDataExtractor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(PlanningContext context, AgentScopeName scopeName) {
        return scopeName == AgentScopeName.PRODUCT
                && context != null
                && context.getExecutionResult() != null
                && context.getExecutionResult().executed();
    }

    @Override
    public Map<String, Object> extract(PlanningContext context, AgentScopeName scopeName) {
        if (!supports(context, scopeName)) {
            logger.info("SaleProduct structuredData skipped sessionId={}, scopeName={}, executed={}",
                    context == null ? "" : context.getSessionId(),
                    scopeName,
                    context != null && context.getExecutionResult() != null && context.getExecutionResult().executed());
            return Map.of();
        }
        String rawPayload = context.getExecutionResult().rawPayload();
        if (rawPayload == null || rawPayload.isBlank()) {
            logger.info("SaleProduct structuredData skipped due to blank payload sessionId={}", context.getSessionId());
            return Map.of();
        }
        try {
            Optional<JsonNode> root = extractJsonNode(rawPayload);
            if (root.isEmpty()) {
                logger.info("SaleProduct structuredData skipped: no JSON candidate sessionId={}, payloadLength={}",
                        context.getSessionId(), rawPayload.length());
                return Map.of();
            }
            if (!containsSaleProductDetail(root.get())) {
                logger.info("SaleProduct structuredData not found in payload sessionId={}, payloadLength={}",
                        context.getSessionId(), rawPayload.length());
                return Map.of();
            }
            Map<String, Object> structuredData = new LinkedHashMap<>();
            structuredData.put("type", "product_detail");
            structuredData.put("productDetail", objectMapper.convertValue(root.get(), Map.class));
            logger.info("SaleProduct structuredData extracted sessionId={}, payloadLength={}",
                    context.getSessionId(), rawPayload.length());
            return structuredData;
        } catch (Exception ex) {
            logger.warn("SaleProduct structuredData extraction failed sessionId={}, error={}",
                    context.getSessionId(), ex.getMessage());
            return Map.of();
        }
    }

    private boolean containsSaleProductDetail(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return false;
        }
        if (node.isObject() && node.has("baseProductInfo") && node.path("baseProductInfo").isObject()) {
            return true;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                if (containsSaleProductDetail(item)) {
                    return true;
                }
            }
            return false;
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                if (containsSaleProductDetail(fields.next().getValue())) {
                    return true;
                }
            }
        }
        if (node.isTextual()) {
            String raw = node.asText("");
            if (raw.startsWith("{") || raw.startsWith("[")) {
                try {
                    return containsSaleProductDetail(objectMapper.readTree(raw));
                } catch (Exception ignored) {
                    return false;
                }
            }
        }
        return false;
    }

    private Optional<JsonNode> extractJsonNode(String rawPayload) {
        try {
            return Optional.of(objectMapper.readTree(rawPayload));
        } catch (Exception ignored) {
            // fallback to embedded JSON scan
        }
        for (int index = 0; index < rawPayload.length(); index++) {
            char ch = rawPayload.charAt(index);
            if (ch != '{' && ch != '[') {
                continue;
            }
            Optional<String> candidate = extractBalancedJson(rawPayload, index);
            if (candidate.isEmpty()) {
                continue;
            }
            try {
                JsonNode parsed = objectMapper.readTree(candidate.get());
                if (containsSaleProductDetail(parsed)) {
                    return Optional.of(parsed);
                }
            } catch (Exception ignored) {
                // continue scanning
            }
        }
        return Optional.empty();
    }

    private Optional<String> extractBalancedJson(String raw, int startIndex) {
        int depth = 0;
        boolean inString = false;
        boolean escaping = false;
        for (int index = startIndex; index < raw.length(); index++) {
            char ch = raw.charAt(index);
            if (inString) {
                if (escaping) {
                    escaping = false;
                    continue;
                }
                if (ch == '\\') {
                    escaping = true;
                    continue;
                }
                if (ch == '"') {
                    inString = false;
                }
                continue;
            }
            if (ch == '"') {
                inString = true;
                continue;
            }
            if (ch == '{' || ch == '[') {
                depth++;
                continue;
            }
            if (ch == '}' || ch == ']') {
                depth--;
                if (depth == 0) {
                    return Optional.of(raw.substring(startIndex, index + 1));
                }
            }
        }
        return Optional.empty();
    }
}
