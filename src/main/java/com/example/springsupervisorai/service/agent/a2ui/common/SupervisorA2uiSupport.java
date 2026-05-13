package com.example.springsupervisorai.service.agent.a2ui.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A2UI event and message support utilities.
 * <p>
 * Provides JSONL serialization for A2UI protocol messages and
 * wrap/unwrap helpers for event stream framing.
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
     * Builds an A2UI v0.8 standard envelope.
     * <p>
     * Format:
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
     * @param textMessage      human-readable summary text
     * @param catalogId        A2UI catalog identifier
     * @param protocolMessages list of A2UI protocol messages (surfaceUpdate, dataModelUpdate, beginRendering, etc.)
     * @param meta             metadata map with sessionId, taskId, sourceAgent, schemaValidated, missingFields
     * @return envelope map
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
     * Creates meta block for A2UI envelope.
     *
     * @param sessionId        session identifier
     * @param taskId           task identifier
     * @param sourceAgent      source agent name
     * @param schemaValidated  whether schema validation was performed
     * @param missingFields    list of missing field names (may be null)
     * @return meta map
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
     * Wraps a payload string with the A2UI event prefix for SSE framing.
     */
    public static String wrap(String payload) {
        return EVENT_PREFIX + (payload == null ? "" : payload);
    }

    /**
     * Marks the given protocol payload array as an A2UI event string.
     * Alias for wrap, used for semantic clarity.
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
     * Serializes a list of A2UI protocol messages (Map) into JSONL format.
     * Each map is serialized as a single JSON line.
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
     * Deserializes a JSONL string back into a list of protocol message maps.
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
     * Wraps a list of A2UI protocol messages into an assistant-role content block
     * that embeds the protocol payload in JSONL format alongside the human-readable message.
     * The format is compatible with Spring AI API message representation.
     *
     * @param textMessage      the human-readable assistant message text
     * @param protocolMessages the A2UI protocol messages (surfaceUpdate, dataModelUpdate, beginRendering, etc.)
     * @param objectMapper     ObjectMapper for serialization
     * @return a Spring AI API-compatible message map with role, content, and protocol payload
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
     * Converts an assistant message map (as produced by toAssistantMessage) back to its
     * protocol payload JSONL and text parts.
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
