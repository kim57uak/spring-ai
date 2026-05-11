package com.example.springsupervisorai.controller;

import com.example.springsupervisorai.a2a.dto.JsonRpcRequest;
import com.example.springsupervisorai.a2a.dto.JsonRpcResponse;
import com.example.springsupervisorai.a2a.dto.TaskIdParams;
import com.example.springsupervisorai.a2a.dto.TaskReviewDecisionParams;
import com.example.springsupervisorai.a2a.dto.TaskReviewGetParams;
import com.example.springsupervisorai.a2a.dto.TaskQueryParams;
import com.example.springsupervisorai.a2a.dto.TaskSendParams;
import com.example.springsupervisorai.a2a.dto.TasksListParams;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.Map;

/**
 * Supervisor A2A JSON-RPC 요청의 method별 params 스키마 검증기.
 */
@Component
public class SupervisorA2ARequestValidator {

    public static final int INVALID_REQUEST = -32600;
    public static final int INVALID_PARAMS = -32602;
    public static final String A2UI_SUBMIT_ACTION_MARKER = "A2UI_SUBMIT_ACTION:";

    private static final int DEFAULT_LIST_LIMIT = 20;
    private static final int MAX_LIST_LIMIT = 200;
    private static final java.util.Set<String> ALLOWED_REVIEW_DECISIONS = java.util.Set.of("APPROVE", "CANCEL", "REVISE");

    /**
     * JSON-RPC 공통 precheck를 수행한다.
     *
     * @param request 입력 요청
     * @return 오류 응답 또는 null
     */
    public JsonRpcResponse precheck(JsonRpcRequest request) {
        if (request == null || !request.isJsonRpc2()) {
            return JsonRpcResponse.error(null, INVALID_REQUEST, "Invalid JSON-RPC request");
        }
        if (request.method() == null || request.method().isBlank()) {
            return JsonRpcResponse.error(request.id(), INVALID_REQUEST, "Method is required");
        }
        return null;
    }

    /**
     * message/send|stream params 스키마를 검증한다.
     * <p>
     * 호환 전략:
     * - legacy: `params.messageText`
     * - v1.0: `params.message.parts[].text`
     * 둘 중 하나라도 유효하면 내부 공통 포맷(ResolvedSendParams)으로 정규화한다.
     *
     * @param request JSON-RPC 요청
     * @param objectMapper JSON 매퍼
     * @return 검증 결과
     */
    public ValidationResult<ResolvedSendParams> validateSendParams(JsonRpcRequest request, ObjectMapper objectMapper) {
        TaskSendParams legacyParams;
        try {
            legacyParams = request.paramsAs(objectMapper, TaskSendParams.class);
        } catch (RuntimeException ex) {
            legacyParams = null;
        }

        if (legacyParams != null && legacyParams.messageText() != null && !legacyParams.messageText().isBlank()) {
            return ValidationResult.ok(new ResolvedSendParams(legacyParams.messageText(), legacyParams.model()));
        }

        JsonNode paramsNode = request.params();
        if (paramsNode == null || paramsNode.isNull()) {
            return ValidationResult.error(JsonRpcResponse.error(
                    request.id(),
                    INVALID_PARAMS,
                    "messageText is required (or message.parts[].text for v1.0)"
            ));
        }
        JsonNode messageNode = paramsNode.path("message");
        String extractedText = extractTextFromMessage(messageNode);
        if (extractedText.isBlank()) {
            extractedText = extractTextFromA2uiAction(messageNode);
        }
        if (extractedText.isBlank()) {
            return ValidationResult.error(JsonRpcResponse.error(
                    request.id(),
                    INVALID_PARAMS,
                    "messageText is required (or message.parts[].text / A2UI userAction for v1.0)"
            ));
        }
        String model = paramsNode.path("model").asText("");
        return ValidationResult.ok(new ResolvedSendParams(extractedText, model.isBlank() ? null : model));
    }

    /**
     * v1.0 `message.parts[]`에서 text 파트를 추출한다.
     * <p>
     * 텍스트 파트가 여러 개면 줄바꿈으로 병합하여
     * 기존 내부 처리(messageText 단일 문자열)와 호환시킨다.
     *
     * @param messageNode v1.0 message 노드
     * @return 병합된 텍스트(없으면 빈 문자열)
     */
    private String extractTextFromMessage(JsonNode messageNode) {
        if (messageNode == null || messageNode.isMissingNode() || messageNode.isNull()) {
            return "";
        }
        JsonNode partsNode = messageNode.path("parts");
        if (!partsNode.isArray()) {
            return "";
        }
        StringBuilder merged = new StringBuilder();
        for (JsonNode part : partsNode) {
            String type = part.path("type").asText("");
            String kind = part.path("kind").asText("");
            if ((!type.isBlank() && !"text".equalsIgnoreCase(type))
                    || (!kind.isBlank() && !"text".equalsIgnoreCase(kind))) {
                continue;
            }
            String text = part.path("text").asText("");
            if (text.isBlank()) {
                continue;
            }
            if (!merged.isEmpty()) {
                merged.append('\n');
            }
            merged.append(text.trim());
        }
        return merged.toString().trim();
    }

    private String extractTextFromA2uiAction(JsonNode messageNode) {
        if (messageNode == null || messageNode.isMissingNode() || messageNode.isNull()) {
            return "";
        }
        JsonNode partsNode = messageNode.path("parts");
        if (!partsNode.isArray()) {
            return "";
        }
        for (JsonNode part : partsNode) {
            if (!isA2uiDataPart(part)) {
                continue;
            }
            JsonNode dataNode = part.path("data");
            JsonNode actionNode = dataNode.path("userAction");
            if (actionNode.isMissingNode() || actionNode.isNull()) {
                actionNode = dataNode.path("action");
            }
            if (actionNode.isMissingNode() || actionNode.isNull()) {
                continue;
            }
            String actionName = actionNode.path("name").asText("").trim();
            JsonNode contextNode = actionNode.path("context");
            if ("submit_reservation".equals(actionName)) {
                return reservationPromptFromContext(contextNode).trim();
            }
            if ("submit_product_creation".equals(actionName)) {
                return productCreationPromptFromContext(contextNode).trim();
            }
            StringBuilder builder = new StringBuilder("A2UI user action");
            if (!actionName.isBlank()) {
                builder.append(": ").append(actionName);
            }
            String surfaceId = actionNode.path("surfaceId").asText("").trim();
            if (!surfaceId.isBlank()) {
                builder.append("\nsurfaceId: ").append(surfaceId);
            }
            if (contextNode.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = contextNode.properties().iterator();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> entry = fields.next();
                    builder.append("\n").append(entry.getKey()).append(": ").append(asFlatText(entry.getValue()));
                }
            }
            return builder.toString().trim();
        }
        return "";
    }

    private boolean isA2uiDataPart(JsonNode part) {
        if (part == null || part.isNull()) {
            return false;
        }
        String kind = part.path("kind").asText(part.path("type").asText("")).trim();
        if (!kind.isBlank() && !"data".equalsIgnoreCase(kind)) {
            return false;
        }
        String mediaType = part.path("mediaType").asText("").trim();
        String mimeType = part.path("metadata").path("mimeType").asText("").trim();
        return "application/json+a2ui".equalsIgnoreCase(mediaType)
                || "application/json+a2ui".equalsIgnoreCase(mimeType);
    }

    private String reservationPromptFromContext(JsonNode contextNode) {
        if (contextNode == null || contextNode.isNull() || !contextNode.isObject()) {
            return "";
        }
        String productCode = contextNode.path("productCode").asText("").trim();
        if (productCode.isBlank()) {
            return "";
        }
        String bookerName = contextNode.path("bookerName").asText("").trim();
        String headCount = contextNode.path("headCount").asText("").trim();
        return String.join("\n",
                A2UI_SUBMIT_ACTION_MARKER + " submit_reservation",
                productCode + " 상품으로 예약 생성 부탁드립니다.",
                "예약자는 " + bookerName + "님입니다.",
                "인원은 " + headCount + "명입니다."
        );
    }

    private String productCreationPromptFromContext(JsonNode contextNode) {
        if (contextNode == null || contextNode.isNull() || !contextNode.isObject()) {
            return "";
        }
        String saleProductCode = contextNode.path("saleProductCode").asText("").trim();
        if (saleProductCode.isBlank()) {
            return "";
        }
        String departureStartDay = contextNode.path("departureStartDay").asText("").trim();
        String departureEndDay = contextNode.path("departureEndDay").asText("").trim();
        String allTarget = ynText(contextNode.path("allTarget"));
        String departureDays = flattenDepartureDays(contextNode.path("departureDays"));
        return String.join("\n",
                A2UI_SUBMIT_ACTION_MARKER + " submit_product_creation",
                saleProductCode + " 상품 생성 부탁드립니다.",
                "출발 기간은 " + departureStartDay + "부터 " + departureEndDay + "까지입니다.",
                "전체 대상 여부는 " + allTarget + "이고, 출발 요일은 " + departureDays + "입니다."
        );
    }

    private String flattenDepartureDays(JsonNode node) {
        if (node == null || node.isNull()) {
            return "";
        }
        if (node.isArray()) {
            java.util.List<String> values = new java.util.ArrayList<>();
            for (JsonNode item : node) {
                String value = item.asText("").trim();
                if (!value.isBlank()) {
                    values.add(value);
                }
            }
            return String.join(", ", values);
        }
        return node.asText("").trim();
    }

    private String ynText(JsonNode node) {
        if (node == null || node.isNull()) {
            return "";
        }
        if (node.isBoolean()) {
            return node.asBoolean() ? "Y" : "N";
        }
        String value = node.asText("").trim();
        if (value.isBlank()) {
            return "";
        }
        return "true".equalsIgnoreCase(value) ? "Y" : "false".equalsIgnoreCase(value) ? "N" : value;
    }

    private String asFlatText(JsonNode value) {
        if (value == null || value.isNull()) {
            return "";
        }
        if (value.isValueNode()) {
            return value.asText("");
        }
        return value.toString();
    }

    /**
     * tasks/get params 스키마를 검증한다.
     *
     * @param request JSON-RPC 요청
     * @param objectMapper JSON 매퍼
     * @return 검증 결과
     */
    public ValidationResult<TaskQueryParams> validateTaskQuery(JsonRpcRequest request, ObjectMapper objectMapper) {
        TaskQueryParams params;
        try {
            params = request.paramsAs(objectMapper, TaskQueryParams.class);
        } catch (RuntimeException ex) {
            return ValidationResult.error(JsonRpcResponse.error(request.id(), INVALID_PARAMS, "Invalid params schema"));
        }
        if (params == null || params.id() == null || params.id().isBlank()) {
            return ValidationResult.error(JsonRpcResponse.error(request.id(), INVALID_PARAMS, "id is required"));
        }
        return ValidationResult.ok(params);
    }

    /**
     * tasks/cancel params 스키마를 검증한다.
     *
     * @param request JSON-RPC 요청
     * @param objectMapper JSON 매퍼
     * @return 검증 결과
     */
    public ValidationResult<TaskIdParams> validateTaskId(JsonRpcRequest request, ObjectMapper objectMapper) {
        TaskIdParams params;
        try {
            params = request.paramsAs(objectMapper, TaskIdParams.class);
        } catch (RuntimeException ex) {
            return ValidationResult.error(JsonRpcResponse.error(request.id(), INVALID_PARAMS, "Invalid params schema"));
        }
        if (params == null || params.id() == null || params.id().isBlank()) {
            return ValidationResult.error(JsonRpcResponse.error(request.id(), INVALID_PARAMS, "id is required"));
        }
        return ValidationResult.ok(params);
    }

    /**
     * tasks/list params 스키마를 검증한다.
     *
     * @param request JSON-RPC 요청
     * @param objectMapper JSON 매퍼
     * @return 검증 결과
     */
    public ValidationResult<TasksListParams> validateList(JsonRpcRequest request, ObjectMapper objectMapper) {
        TasksListParams params;
        try {
            params = request.paramsAs(objectMapper, TasksListParams.class);
        } catch (RuntimeException ex) {
            return ValidationResult.error(JsonRpcResponse.error(request.id(), INVALID_PARAMS, "Invalid params schema"));
        }
        if (params == null || params.limit() == null) {
            return ValidationResult.ok(new TasksListParams(DEFAULT_LIST_LIMIT));
        }
        if (params.limit() < 1 || params.limit() > MAX_LIST_LIMIT) {
            return ValidationResult.error(
                    JsonRpcResponse.error(request.id(), INVALID_PARAMS, "limit must be between 1 and 200")
            );
        }
        return ValidationResult.ok(params);
    }

    public ValidationResult<TaskReviewGetParams> validateReviewGet(JsonRpcRequest request, ObjectMapper objectMapper) {
        TaskReviewGetParams params;
        try {
            params = request.paramsAs(objectMapper, TaskReviewGetParams.class);
        } catch (RuntimeException ex) {
            return ValidationResult.error(JsonRpcResponse.error(request.id(), INVALID_PARAMS, "Invalid params schema"));
        }
        if (params == null || params.id() == null || params.id().isBlank()) {
            return ValidationResult.error(JsonRpcResponse.error(request.id(), INVALID_PARAMS, "id is required"));
        }
        return ValidationResult.ok(params);
    }

    public ValidationResult<TaskReviewDecisionParams> validateReviewDecision(JsonRpcRequest request, ObjectMapper objectMapper) {
        TaskReviewDecisionParams params;
        try {
            params = request.paramsAs(objectMapper, TaskReviewDecisionParams.class);
        } catch (RuntimeException ex) {
            return ValidationResult.error(JsonRpcResponse.error(request.id(), INVALID_PARAMS, "Invalid params schema"));
        }
        if (params == null || params.id() == null || params.id().isBlank()) {
            return ValidationResult.error(JsonRpcResponse.error(request.id(), INVALID_PARAMS, "id is required"));
        }
        String normalized = params.decision() == null ? "" : params.decision().trim().toUpperCase(java.util.Locale.ROOT);
        if (!ALLOWED_REVIEW_DECISIONS.contains(normalized)) {
            return ValidationResult.error(JsonRpcResponse.error(request.id(), INVALID_PARAMS, "decision must be APPROVE, CANCEL or REVISE"));
        }
        return ValidationResult.ok(new TaskReviewDecisionParams(
                params.id(),
                normalized,
                params.reason(),
                params.decisionId(),
                params.revisedMessage()
        ));
    }

    /**
     * 검증 성공/실패를 전달하는 값 객체.
     *
     * @param params 검증 통과 파라미터
     * @param error 검증 실패 시 오류 응답
     * @param <T> 파라미터 타입
     */
    public record ValidationResult<T>(T params, JsonRpcResponse error) {
        /**
         * 검증 성공 결과를 생성한다.
         *
         * @param params 검증 통과 파라미터
         * @param <T> 파라미터 타입
         * @return 성공 결과 객체
         */
        public static <T> ValidationResult<T> ok(T params) {
            return new ValidationResult<>(params, null);
        }

        /**
         * 검증 실패 결과를 생성한다.
         *
         * @param error JSON-RPC 오류 응답
         * @param <T> 파라미터 타입
         * @return 실패 결과 객체
         */
        public static <T> ValidationResult<T> error(JsonRpcResponse error) {
            return new ValidationResult<>(null, error);
        }

        /**
         * 검증 실패 여부를 반환한다.
         *
         * @return 오류 응답이 있으면 true
         */
        public boolean isError() {
            return error != null;
        }
    }

    /**
     * send/stream 처리에 사용하는 내부 표준 파라미터.
     * <p>
     * 목적:
     * - 입력 스키마가 legacy/v1.0으로 달라도 이후 서비스 레이어는
     *   동일한 `messageText + model` 형태만 다루도록 단순화한다.
     *
     * @param messageText 정규화된 사용자 텍스트
     * @param model 선택 모델
     */
    public record ResolvedSendParams(String messageText, String model) {
    }
}
