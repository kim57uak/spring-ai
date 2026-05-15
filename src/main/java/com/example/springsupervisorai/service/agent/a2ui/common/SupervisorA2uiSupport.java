package com.example.springsupervisorai.service.agent.a2ui.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A2UI 이벤트 및 메시지 지원 유틸리티.
 * <p>
 * A2UI 프로토콜 메시지의 JSONL 직렬화와
 * 이벤트 스트림 프레이밍을 위한 wrap/unwrap 헬퍼를 제공한다.
 */
public final class SupervisorA2uiSupport {

    public static final String EVENT_PREFIX = "[[A2UI]]";
    public static final String A2UI_PROTOCOL_MARKER = "a2ui_protocol";
    public static final String A2UI_CONTENT_TYPE = "application/x-a2ui-jsonl";
    public static final String A2UI_PROTOCOL_VERSION = "0.8";
    public static final String ENVELOPE_VERSION = "1.0";

    private static final ObjectMapper SHARED_MAPPER = new ObjectMapper();

    private SupervisorA2uiSupport() {
    }

    /**
     * A2UI v0.8 표준 봉투를 빌드한다.
     * <p>
     * 형식:
     * <pre>
     * {
     *   "version": "1.0",
     *   "message": "human-readable summary",
     *   "a2ui": {
     *     "protocolVersion": "0.8",
     *     "catalogId": "https://...",
     *     "messages": [...]
     *   },
     *   "meta": {
     *     "sessionId": "...",
     *     "taskId": "...",
     *     "sourceAgent": "...",
     *     "schemaValidated": true,
     *     "missingFields": [...]
     *   }
     * }
     * </pre>
     *
     * @param textMessage      사람이 읽을 수 있는 요약 텍스트
     * @param catalogId        A2UI 카탈로그 식별자
     * @param protocolMessages A2UI 프로토콜 메시지 목록 (surfaceUpdate, dataModelUpdate, beginRendering 등)
     * @param meta             sessionId, taskId, sourceAgent, schemaValidated, missingFields를 포함한 메타데이터 맵
     * @return 봉투 맵
     */
    public static Map<String, Object> buildEnvelope(
            String textMessage,
            String catalogId,
            List<Map<String, Object>> protocolMessages,
            Map<String, Object> meta
    ) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("version", ENVELOPE_VERSION);
        envelope.put("message", textMessage == null ? "" : textMessage);

        if (protocolMessages != null && !protocolMessages.isEmpty()) {
            Map<String, Object> a2ui = new LinkedHashMap<>();
            a2ui.put("protocolVersion", A2UI_PROTOCOL_VERSION);
            if (catalogId != null && !catalogId.isBlank()) {
                a2ui.put("catalogId", catalogId);
            }
            a2ui.put("messages", List.copyOf(protocolMessages));
            envelope.put("a2ui", a2ui);
        }

        if (meta != null && !meta.isEmpty()) {
            envelope.put("meta", Map.copyOf(meta));
        }

        return Map.copyOf(envelope);
    }

    /**
     * A2UI 봉투용 메타 블록을 생성한다.
     *
     * @param sessionId        세션 식별자
     * @param taskId           작업 식별자
     * @param sourceAgent      소스 에이전트 이름
     * @param schemaValidated  스키마 검증 수행 여부
     * @param missingFields    누락된 필드명 목록 (null 가능)
     * @return 메타 맵
     */
    public static Map<String, Object> buildMeta(
            String sessionId,
            String taskId,
            String sourceAgent,
            boolean schemaValidated,
            java.util.List<String> missingFields
    ) {
        Map<String, Object> meta = new LinkedHashMap<>();
        if (sessionId != null) {
            meta.put("sessionId", sessionId);
        }
        if (taskId != null) {
            meta.put("taskId", taskId);
        }
        if (sourceAgent != null) {
            meta.put("sourceAgent", sourceAgent);
        }
        meta.put("schemaValidated", schemaValidated);
        if (missingFields != null && !missingFields.isEmpty()) {
            meta.put("missingFields", List.copyOf(missingFields));
        }
        return Map.copyOf(meta);
    }

    /**
     * SSE 프레이밍을 위해 페이로드 문자열을 A2UI 이벤트 프리픽스로 감싼다.
     */
    public static String wrap(String payload) {
        return EVENT_PREFIX + (payload == null ? "" : payload);
    }

    /**
     * 주어진 프로토콜 페이로드 배열을 A2UI 이벤트 문자열로 표시한다.
     * 의미적 명확성을 위한 wrap의 별칭.
     */
    public static String markA2ui(String payload) {
        return wrap(payload);
    }

    public static boolean isWrapped(String chunk) {
        return chunk != null && chunk.startsWith(EVENT_PREFIX);
    }

    public static String unwrap(String chunk) {
        if (!isWrapped(chunk)) {
            return chunk == null ? "" : chunk;
        }
        return chunk.substring(EVENT_PREFIX.length());
    }

    /**
     * A2UI 프로토콜 메시지(Map) 목록을 JSONL 형식으로 직렬화한다.
     * 각 맵은 단일 JSON 라인으로 직렬화된다.
     */
    public static String toJsonl(List<Map<String, Object>> protocolMessages) {
        if (protocolMessages == null || protocolMessages.isEmpty()) {
            return "";
        }
        try {
            StringBuilder sb = new StringBuilder();
            for (Map<String, Object> msg : protocolMessages) {
                if (msg != null && !msg.isEmpty()) {
                    sb.append(SHARED_MAPPER.writeValueAsString(msg)).append('\n');
                }
            }
            return sb.toString();
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Failed to serialize A2UI protocol messages to JSONL", ex);
        }
    }

    /**
     * JSONL 문자열을 프로토콜 메시지 맵 목록으로 역직렬화한다.
     */
    public static List<Map<String, Object>> fromJsonl(String jsonl) {
        if (jsonl == null || jsonl.isBlank()) {
            return List.of();
        }
        try {
            List<Map<String, Object>> messages = new ArrayList<>();
            for (String line : jsonl.split("\n")) {
                String trimmed = line.trim();
                if (!trimmed.isBlank()) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> parsed = SHARED_MAPPER.readValue(trimmed, Map.class);
                    messages.add(parsed);
                }
            }
            return List.copyOf(messages);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Failed to deserialize A2UI JSONL", ex);
        }
    }

    /**
     * A2UI 프로토콜 메시지 목록을 사람이 읽을 수 있는 메시지와 함께 JSONL 형식의
     * 프로토콜 페이로드를 포함하는 assistant 역할 콘텐츠 블록으로 감싼다.
     * 형식은 Spring AI API 메시지 표현과 호환된다.
     *
     * @param textMessage      사람이 읽을 수 있는 assistant 메시지 텍스트
     * @param protocolMessages A2UI 프로토콜 메시지 (surfaceUpdate, dataModelUpdate, beginRendering 등)
     * @param objectMapper     직렬화용 ObjectMapper
     * @return 역할, 콘텐츠, 프로토콜 페이로드를 포함한 Spring AI API 호환 메시지 맵
     */
    public static Map<String, Object> toAssistantMessage(
            String textMessage,
            List<Map<String, Object>> protocolMessages,
            ObjectMapper objectMapper
    ) {
        String jsonl = toJsonl(protocolMessages);
        return Map.of(
                "role", "assistant",
                "content", textMessage == null ? "" : textMessage,
                A2UI_PROTOCOL_MARKER, jsonl,
                "contentType", A2UI_CONTENT_TYPE
        );
    }

    /**
     * assistant 메시지 맵(toAssistantMessage가 생성한)을
     * 프로토콜 페이로드 JSONL과 텍스트 부분으로 다시 변환한다.
     */
    public record A2uiAssistantParts(String text, String jsonlPayload) {
    }

    public static A2uiAssistantParts extractAssistantParts(Map<String, Object> messageMap) {
        if (messageMap == null) {
            return new A2uiAssistantParts("", "");
        }
        Object role = messageMap.get("role");
        if (!"assistant".equals(role)) {
            return new A2uiAssistantParts("", "");
        }
        String content = messageMap.getOrDefault("content", "").toString();
        Object rawPayload = messageMap.get(A2UI_PROTOCOL_MARKER);
        String jsonl = rawPayload == null ? "" : rawPayload.toString();
        return new A2uiAssistantParts(content, jsonl);
    }
}
