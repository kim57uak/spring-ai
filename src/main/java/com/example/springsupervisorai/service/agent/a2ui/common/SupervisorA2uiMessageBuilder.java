package com.example.springsupervisorai.service.agent.a2ui.common;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A2UI 프로토콜 페이로드에 대한 Spring AI API 호환 assistant 메시지를 빌드한다.
 * <p>
 * 원시 A2UI 프로토콜 메시지 목록(surfaceUpdate, dataModelUpdate, beginRendering 등)을
 * 프로토콜 페이로드를 JSONL 형식으로 포함하는 구조화된 assistant 메시지로 변환한다.
 * 이를 통해 대화 히스토리는 A2UI 상호작용을 일급 Spring AI API 메시지로 추적할 수 있으며,
 * 사람이 읽을 수 있는 텍스트와 구조화된 프로토콜 데이터를 모두 지원한다.
 */
public final class SupervisorA2uiMessageBuilder {

    public static final String A2UI_ROLE_MARKER = "assistant-a2ui";
    public static final String LEGACY_STORAGE_PREFIX = "a2ui_assistant: ";

    private final ObjectMapper objectMapper;

    public SupervisorA2uiMessageBuilder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * A2UI 프로토콜 메시지로부터 구조화된 assistant 메시지를 빌드한다.
     * <p>
     * 결과는 사람이 읽을 수 있는 텍스트와 프로토콜 페이로드를 별도 필드로 포함하며,
     * SSE 전송 및 대화 저장소 모두에 적합하다.
     *
     * @param textMessage      사람이 읽을 수 있는 assistant 메시지 텍스트
     * @param protocolMessages 도메인별 빌더가 생성한 A2UI 프로토콜 메시지
     * @return Spring AI API 메시지 구조와 호환되는 메시지 맵
     */
    public Map<String, Object> build(String textMessage, List<Map<String, Object>> protocolMessages) {
        return SupervisorA2uiSupport.toAssistantMessage(textMessage, protocolMessages, objectMapper);
    }

    /**
     * assistant 메시지를 JSON 문자열로 직렬화한다.
     */
    public String serialize(Map<String, Object> assistantMessage) {
        try {
            return objectMapper.writeValueAsString(assistantMessage);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Failed to serialize A2UI assistant message", ex);
        }
    }

    /**
     * JSON 문자열을 메시지 맵으로 역직렬화한다.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> deserialize(String json) {
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Failed to deserialize A2UI assistant message", ex);
        }
    }

    /**
     * 직렬화된 A2UI assistant 메시지에서 텍스트 내용을 추출한다.
     */
    public Optional<String> extractText(String serializedMessage) {
        try {
            Map<String, Object> parsed = deserialize(serializedMessage);
            return Optional.ofNullable(parsed.get("content")).map(Object::toString);
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    /**
     * 직렬화된 A2UI assistant 메시지에서 프로토콜 페이로드(JSONL)를 추출한다.
     */
    public Optional<String> extractProtocolJsonl(String serializedMessage) {
        try {
            Map<String, Object> parsed = deserialize(serializedMessage);
            Object raw = parsed.get(SupervisorA2uiSupport.A2UI_PROTOCOL_MARKER);
            if (raw == null) {
                return Optional.empty();
            }
            String jsonl = raw.toString();
            return jsonl.isBlank() ? Optional.empty() : Optional.of(jsonl);
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    /**
     * Spring AI API 호환 메시지를 기존 대화 저장소와의 하위 호환성을 위해
     * 레거시 저장소 형식으로 변환한다.
     * <p>
     * 형식: {@code a2ui_assistant: <text message>}
     */
    public String toLegacyStorage(Map<String, Object> assistantMessage) {
        String text = (String) assistantMessage.getOrDefault("content", "");
        return LEGACY_STORAGE_PREFIX + text;
    }

    /**
     * 레거시 저장소 항목이 A2UI assistant 메시지인지 확인한다.
     */
    public static boolean isLegacyA2uiEntry(String entry) {
        return entry != null && entry.startsWith(LEGACY_STORAGE_PREFIX);
    }

    /**
     * 레거시 저장소 A2UI 항목에서 텍스트 내용을 추출한다.
     */
    public static String extractLegacyText(String entry) {
        if (!isLegacyA2uiEntry(entry)) {
            return entry == null ? "" : entry;
        }
        return entry.substring(LEGACY_STORAGE_PREFIX.length());
    }

    /**
     * A2UI 프로토콜 메시지 목록과 텍스트 메시지를 레거시 스타일 저장소 항목으로 변환한다
     * (JSON 저장소를 지원하지 않는 시스템용).
     *
     * @param textMessage      사람이 읽을 수 있는 텍스트
     * @param protocolMessages A2UI 프로토콜 메시지
     * @return 레거시 형식 문자열: "a2ui_assistant: <text>"
     */
    public String toLegacyStorage(String textMessage, List<Map<String, Object>> protocolMessages) {
        return toLegacyStorage(build(textMessage, protocolMessages));
    }

    /**
     * A2UI 프로토콜 메시지 목록을 JSONL로 직접 직렬화한다.
     */
    public String toJsonl(List<Map<String, Object>> protocolMessages) {
        return SupervisorA2uiSupport.toJsonl(protocolMessages);
    }

    /**
     * JSONL을 프로토콜 메시지로 역직렬화한다.
     */
    public List<Map<String, Object>> fromJsonl(String jsonl) {
        return SupervisorA2uiSupport.fromJsonl(jsonl);
    }

    /**
     * Spring AI ChatClient를 통한 직렬화에 적합한 역할 마커가 있는 맵을 생성한다.
     */
    public Map<String, Object> createToolCallResultMessage(String toolName, String toolResult) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", "tool");
        msg.put("toolName", toolName);
        msg.put("content", toolResult == null ? "" : toolResult);
        return msg;
    }
}
