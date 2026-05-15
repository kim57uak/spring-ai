package com.example.springsupervisorai.service.agent.a2ui.product;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.Optional;

/**
 * 이기종 downstream 페이로드 형태에서 제품 상세 노드를 추출한다.
 */
@Component
public class ProductPayloadExtractor {

    private static final Logger logger = LoggerFactory.getLogger(ProductPayloadExtractor.class);

    private final ObjectMapper objectMapper;

    public ProductPayloadExtractor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Optional<JsonNode> extractProductNode(String payload) {
        if (payload == null || payload.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(payload);
            JsonNode structuredDataProduct = root.path("structuredData").path("productDetail");
            if (!structuredDataProduct.isMissingNode() && !structuredDataProduct.isNull()) {
                logger.info("Supervisor product A2UI found structuredData.productDetail");
                Optional<JsonNode> structuredFound = findProductNode(structuredDataProduct);
                if (structuredFound.isPresent()) {
                    return structuredFound;
                }
            }
            logger.info("Supervisor product A2UI falling back to raw payload scan");
            return findProductNode(root);
        } catch (Exception ex) {
            logger.warn("Supervisor product A2UI payload parse failed error={}", ex.getMessage());
            return Optional.empty();
        }
    }

    private Optional<JsonNode> findProductNode(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return Optional.empty();
        }
        if (node.isObject() && node.has("baseProductInfo") && node.path("baseProductInfo").isObject()) {
            return Optional.of(node);
        }
        if (node.isObject() && isCreationFormPayload(node)) {
            return Optional.of(node);
        }
        if (node.isTextual()) {
            String raw = node.asText("");
            if (raw.startsWith("{") || raw.startsWith("[")) {
                try {
                    return findProductNode(objectMapper.readTree(raw));
                } catch (Exception ignored) {
                    return Optional.empty();
                }
            }
            return Optional.empty();
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                Optional<JsonNode> found = findProductNode(item);
                if (found.isPresent()) {
                    return found;
                }
            }
            return Optional.empty();
        }
        if (node.isObject()) {
            Iterator<JsonNode> values = node.elements();
            while (values.hasNext()) {
                Optional<JsonNode> found = findProductNode(values.next());
                if (found.isPresent()) {
                    return found;
                }
            }
        }
        return Optional.empty();
    }

    /**
     * 상품 생성 입력값을 담은 평면 payload도 product A2UI 입력 폼으로 승격한다.
     */
    private boolean isCreationFormPayload(JsonNode node) {
        return hasText(node, "saleProductCode")
                && (hasText(node, "departureStartDay") || hasText(node, "departureEndDay"));
    }

    private boolean hasText(JsonNode node, String field) {
        return !node.path(field).asText("").trim().isBlank();
    }
}
