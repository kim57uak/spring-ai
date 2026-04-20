package com.example.springsupervisorai.service.agent.result;

import com.example.springsupervisorai.model.DownstreamCallResult;
import com.example.springsupervisorai.model.SupervisorInvocationStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Locale;
import java.util.Set;

/**
 * downstream 호출 결과를 성공/실패/불명확 상태로 해석하는 유틸리티.
 * <p>
 * 판정 기준:
 * - errorCode 존재 시 실패
 * - status가 명시적 실패 값이면 실패
 * - payload 내 [ERROR] 또는 error/errorCode 신호가 감지되면 실패
 * - status가 명시적 성공 값이면 성공
 * - 그 외는 UNKNOWN
 */
public final class DownstreamResultInterpreter {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int MAX_JSON_SCAN_DEPTH = 5;
    private static final Set<String> SUCCESS_STATUS = Set.of("COMPLETED", "SUCCESS");
    private static final Set<String> FAILURE_STATUS = Set.of("FAILED", "ERROR", "CANCELED", "CANCELLED", "TIMEOUT", "REJECTED");
    private static final Set<String> FAILURE_TEXT_MARKERS = Set.of(
            "[error]",
            "[missing_required_params]",
            "[policy_skipped]",
            "도구 사용 결과: 실패",
            "실행 결과: 실패"
    );

    private DownstreamResultInterpreter() {
    }

    /**
     * 단일 downstream 결과를 판정한다.
     *
     * @param result downstream 결과
     * @return 판정 결과
     */
    public static Assessment assess(DownstreamCallResult result) {
        if (result == null) {
            return Assessment.failed("result_missing");
        }

        String errorCode = safe(result.errorCode());
        if (!errorCode.isBlank()) {
            return Assessment.failed("error_code:" + errorCode);
        }

        String status = normalizeStatus(result.status());
        if (FAILURE_STATUS.contains(status)) {
            return Assessment.failed("status:" + status);
        }

        if (payloadSignalsFailure(result.payload())) {
            return Assessment.failed("payload:" + payloadFailureReason(result.payload()));
        }

        if (SUCCESS_STATUS.contains(status)) {
            return Assessment.success("status:" + status);
        }

        if (status.isBlank()) {
            return Assessment.unknown("status_missing");
        }
        return Assessment.unknown("status:" + status);
    }

    /**
     * payload 본문에서 실패 신호를 탐지한다.
     *
     * @param payload downstream payload 문자열
     * @return 실패 신호가 감지되면 true
     */
    public static boolean payloadSignalsFailure(String payload) {
        String text = safe(payload);
        if (text.isBlank()) {
            return false;
        }
        if (containsFailureMarker(text)) {
            return true;
        }
        try {
            JsonNode node = OBJECT_MAPPER.readTree(text);
            return jsonSignalsFailure(node, 0);
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * payload 실패 신호를 간단한 사유 문자열로 반환한다.
     *
     * @param payload downstream payload 문자열
     * @return 실패 사유 코드
     */
    public static String payloadFailureReason(String payload) {
        String text = safe(payload);
        if (text.isBlank()) {
            return "empty_payload";
        }
        String markerReason = failureMarkerReason(text);
        if (!markerReason.isBlank()) {
            return markerReason;
        }
        try {
            JsonNode node = OBJECT_MAPPER.readTree(text);
            String jsonReason = jsonFailureReason(node, 0);
            return jsonReason.isBlank() ? "json_failure_signal" : jsonReason;
        } catch (Exception ignored) {
            return "failure_signal";
        }
    }

    private static boolean jsonSignalsFailure(JsonNode node, int depth) {
        if (node == null || node.isNull() || depth > MAX_JSON_SCAN_DEPTH) {
            return false;
        }
        if (node.isTextual()) {
            return containsFailureMarker(node.asText(""));
        }
        if (node.isObject()) {
            String status = normalizeStatus(node.path("status").asText(""));
            if (FAILURE_STATUS.contains(status)) {
                return true;
            }
            String errorCode = safe(node.path("errorCode").asText(""));
            if (!errorCode.isBlank()) {
                return true;
            }
            JsonNode errorNode = node.path("error");
            if (!errorNode.isMissingNode() && !errorNode.isNull() && !errorNode.toString().trim().equals("{}")) {
                return true;
            }
            var fieldNames = node.fieldNames();
            while (fieldNames.hasNext()) {
                String fieldName = fieldNames.next();
                if (jsonSignalsFailure(node.path(fieldName), depth + 1)) {
                    return true;
                }
            }
            return false;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                if (jsonSignalsFailure(child, depth + 1)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String jsonFailureReason(JsonNode node, int depth) {
        if (node == null || node.isNull() || depth > MAX_JSON_SCAN_DEPTH) {
            return "";
        }
        if (node.isTextual()) {
            return failureMarkerReason(node.asText(""));
        }
        if (node.isObject()) {
            String status = normalizeStatus(node.path("status").asText(""));
            if (FAILURE_STATUS.contains(status)) {
                return "status:" + status;
            }
            String errorCode = safe(node.path("errorCode").asText(""));
            if (!errorCode.isBlank()) {
                return "error_code:" + errorCode;
            }
            JsonNode errorNode = node.path("error");
            if (!errorNode.isMissingNode() && !errorNode.isNull() && !errorNode.toString().trim().equals("{}")) {
                return "error_object";
            }
            var fieldNames = node.fieldNames();
            while (fieldNames.hasNext()) {
                String fieldName = fieldNames.next();
                String nested = jsonFailureReason(node.path(fieldName), depth + 1);
                if (!nested.isBlank()) {
                    return nested;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                String nested = jsonFailureReason(child, depth + 1);
                if (!nested.isBlank()) {
                    return nested;
                }
            }
        }
        return "";
    }

    private static String normalizeStatus(String status) {
        return safe(status).toUpperCase(Locale.ROOT);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean containsFailureMarker(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String marker : FAILURE_TEXT_MARKERS) {
            if (lower.contains(marker.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static String failureMarkerReason(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("[error]")) {
            return "error_token";
        }
        if (lower.contains("[missing_required_params]")) {
            return "missing_required_params";
        }
        if (lower.contains("[policy_skipped]")) {
            return "policy_skipped";
        }
        if (lower.contains("도구 사용 결과: 실패")) {
            return "tool_result_failed_marker";
        }
        if (lower.contains("실행 결과: 실패")) {
            return "execution_result_failed_marker";
        }
        return "";
    }

    /**
     * downstream 결과 판정 DTO.
     *
     * @param outcome 판정 결과
     * @param reason 판정 사유
     */
    public record Assessment(
            Outcome outcome,
            String reason
    ) {
        private static Assessment success(String reason) {
            return new Assessment(Outcome.SUCCESS, safeReason(reason));
        }

        private static Assessment failed(String reason) {
            return new Assessment(Outcome.FAILED, safeReason(reason));
        }

        private static Assessment unknown(String reason) {
            return new Assessment(Outcome.UNKNOWN, safeReason(reason));
        }

        private static String safeReason(String value) {
            return value == null ? "" : value.trim();
        }
    }

    /**
     * downstream 결과 정규화 판정 값.
     */
    public enum Outcome {
        SUCCESS,
        FAILED,
        UNKNOWN
    }
}
