package com.example.springsupervisorai.service.agent.a2ui.common;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Builds Spring AI API-compatible assistant messages for A2UI protocol payloads.
 * <p>
 * Converts raw A2UI protocol message lists (surfaceUpdate, dataModelUpdate, beginRendering, etc.)
 * into a structured assistant message that embeds the protocol payload in JSONL format.
 * This enables the conversation history to track A2UI interactions as first-class
 * Spring AI API messages, supporting both human-readable text and structured protocol data.
 */
public final class SupervisorA2uiMessageBuilder {

    public static final String A2UI_ROLE_MARKER = "assistant-a2ui";
    public static final String LEGACY_STORAGE_PREFIX = "a2ui_assistant: ";

    private final ObjectMapper objectMapper;

    public SupervisorA2uiMessageBuilder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Builds a structured assistant message from A2UI protocol messages.
     * <p>
     * The result contains the human-readable text alongside the protocol payload
     * as a separate field, suitable for both SSE transport and conversation storage.
     *
     * @param textMessage      the human-readable assistant message text
     * @param protocolMessages the A2UI protocol messages built by domain-specific builders
     * @return a message map compatible with Spring AI API message structure
     */
    public Map<String, Object> build(String textMessage, List<Map<String, Object>> protocolMessages) {
        return SupervisorA2uiSupport.toAssistantMessage(textMessage, protocolMessages, objectMapper);
    }

    /**
     * Serializes the assistant message to a JSON string.
     */
    public String serialize(Map<String, Object> assistantMessage) {
        try {
            return objectMapper.writeValueAsString(assistantMessage);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Failed to serialize A2UI assistant message", ex);
        }
    }

    /**
     * Deserializes a JSON string back into a message map.
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
     * Extracts the text content from a serialized A2UI assistant message.
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
     * Extracts the protocol payload (JSONL) from a serialized A2UI assistant message.
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
     * Converts the Spring AI API-compatible message to legacy storage format
     * for backward compatibility with the existing conversation store.
     * <p>
     * Format: {@code a2ui_assistant: <text message>}
     */
    public String toLegacyStorage(Map<String, Object> assistantMessage) {
        String text = (String) assistantMessage.getOrDefault("content", "");
        return LEGACY_STORAGE_PREFIX + text;
    }

    /**
     * Checks if a legacy storage entry is an A2UI assistant message.
     */
    public static boolean isLegacyA2uiEntry(String entry) {
        return entry != null && entry.startsWith(LEGACY_STORAGE_PREFIX);
    }

    /**
     * Extracts text content from a legacy storage A2UI entry.
     */
    public static String extractLegacyText(String entry) {
        if (!isLegacyA2uiEntry(entry)) {
            return entry == null ? "" : entry;
        }
        return entry.substring(LEGACY_STORAGE_PREFIX.length());
    }

    /**
     * Converts a list of A2UI protocol messages and a text message into
     * a legacy-style storage entry (for systems that don't support JSON storage).
     *
     * @param textMessage      the human-readable text
     * @param protocolMessages the A2UI protocol messages
     * @return legacy format string: "a2ui_assistant: <text>"
     */
    public String toLegacyStorage(String textMessage, List<Map<String, Object>> protocolMessages) {
        return toLegacyStorage(build(textMessage, protocolMessages));
    }

    /**
     * Serializes a list of A2UI protocol messages directly to JSONL.
     */
    public String toJsonl(List<Map<String, Object>> protocolMessages) {
        return SupervisorA2uiSupport.toJsonl(protocolMessages);
    }

    /**
     * Deserializes JSONL to protocol messages.
     */
    public List<Map<String, Object>> fromJsonl(String jsonl) {
        return SupervisorA2uiSupport.fromJsonl(jsonl);
    }

    /**
     * Creates a map with role marker suitable for serialization via Spring AI ChatClient.
     */
    public Map<String, Object> createToolCallResultMessage(String toolName, String toolResult) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", "tool");
        msg.put("toolName", toolName);
        msg.put("content", toolResult == null ? "" : toolResult);
        return msg;
    }
}
