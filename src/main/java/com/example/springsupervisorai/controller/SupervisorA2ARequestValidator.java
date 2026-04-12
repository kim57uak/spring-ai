package com.example.springsupervisorai.controller;

import com.example.springsupervisorai.a2a.dto.JsonRpcRequest;
import com.example.springsupervisorai.a2a.dto.JsonRpcResponse;
import com.example.springsupervisorai.a2a.dto.TaskIdParams;
import com.example.springsupervisorai.a2a.dto.TaskQueryParams;
import com.example.springsupervisorai.a2a.dto.TaskSendParams;
import com.example.springsupervisorai.a2a.dto.TasksListParams;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * Supervisor A2A JSON-RPC 요청의 method별 params 스키마 검증기.
 */
@Component
public class SupervisorA2ARequestValidator {

    public static final int INVALID_REQUEST = -32600;
    public static final int INVALID_PARAMS = -32602;

    private static final int DEFAULT_LIST_LIMIT = 20;
    private static final int MAX_LIST_LIMIT = 200;

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
            return ValidationResult.error(JsonRpcResponse.error(request.id(), INVALID_PARAMS, "messageText or message.parts[].text is required"));
        }
        JsonNode messageNode = paramsNode.path("message");
        String extractedText = extractTextFromMessage(messageNode);
        if (extractedText.isBlank()) {
            return ValidationResult.error(JsonRpcResponse.error(request.id(), INVALID_PARAMS, "messageText or message.parts[].text is required"));
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
            if (!type.isBlank() && !"text".equalsIgnoreCase(type)) {
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
