package com.example.springsupervisorai.service.agent.a2ui.reservation;

import com.example.springsupervisorai.model.DownstreamCallResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts reservation-form seed data from reservation/product payloads or user message.
 */
@Component
public class ReservationPayloadExtractor {

    private static final Pattern PRODUCT_CODE_PATTERN = Pattern.compile("\\b[A-Z]{3}\\d{8,}[A-Z0-9]*\\b");

    private final ObjectMapper objectMapper;

    public ReservationPayloadExtractor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Optional<ReservationPresentationModel> extract(DownstreamCallResult result, String userMessage) {
        ReservationPresentationModel fromPayload = fromPayload(result == null ? "" : result.payload());
        if (fromPayload != null) {
            return Optional.of(fromPayload);
        }
        String seededProductCode = extractProductCode(userMessage);
        if (seededProductCode.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new ReservationPresentationModel(seededProductCode, "", "", "1"));
    }

    private ReservationPresentationModel fromPayload(String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(payload);
            return findSeed(root);
        } catch (Exception ignored) {
            return null;
        }
    }

    private ReservationPresentationModel findSeed(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isObject()) {
            String productCode = firstText(node, "saleProdCd", "saleProductCode", "productCode");
            String productName = firstText(node, "saleProdNm", "saleProductName", "productName");
            String bookerName = firstText(node, "bookerName", "reservationName", "customerName");
            String headCount = firstText(node, "headCount", "personCount", "adultCount");
            if (!productCode.isBlank()) {
                return new ReservationPresentationModel(
                        productCode,
                        productName,
                        bookerName,
                        headCount.isBlank() ? "1" : headCount
                );
            }
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                ReservationPresentationModel seed = findSeed(item);
                if (seed != null) {
                    return seed;
                }
            }
            return null;
        }
        if (node.isObject()) {
            Iterator<JsonNode> values = node.elements();
            while (values.hasNext()) {
                ReservationPresentationModel seed = findSeed(values.next());
                if (seed != null) {
                    return seed;
                }
            }
        }
        if (node.isTextual()) {
            String raw = node.asText("").trim();
            if (raw.startsWith("{") || raw.startsWith("[")) {
                try {
                    return findSeed(objectMapper.readTree(raw));
                } catch (Exception ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = node.path(field).asText("").trim();
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String extractProductCode(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return "";
        }
        Matcher matcher = PRODUCT_CODE_PATTERN.matcher(userMessage);
        return matcher.find() ? matcher.group() : "";
    }
}
