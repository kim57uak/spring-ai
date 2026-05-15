package com.example.springsupervisorai.model;

/**
 * Supervisor pipeline이 전송 직렬화 전에 방출하는 구조화된 출력 이벤트.
 */
public record SupervisorOutputEvent(
        SupervisorOutputEventType type,
        String content,
        SupervisorProgressEvent progressEvent
) {

    public SupervisorOutputEvent {
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        if (content == null) {
            content = "";
        }
    }

    public static SupervisorOutputEvent progress(SupervisorProgressEvent event) {
        return new SupervisorOutputEvent(SupervisorOutputEventType.PROGRESS, "", event);
    }

    public static SupervisorOutputEvent text(String content) {
        return new SupervisorOutputEvent(SupervisorOutputEventType.TEXT, content, null);
    }

    public static SupervisorOutputEvent a2ui(String payloadJson) {
        return new SupervisorOutputEvent(SupervisorOutputEventType.A2UI, payloadJson, null);
    }

    public static SupervisorOutputEvent error(String content) {
        return new SupervisorOutputEvent(SupervisorOutputEventType.ERROR, content, null);
    }
}
