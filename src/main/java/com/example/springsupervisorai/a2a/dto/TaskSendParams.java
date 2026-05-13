package com.example.springsupervisorai.a2a.dto;

import java.util.Map;

public record TaskSendParams(
        String messageText,
        String model,
        String role,
        String messageId,
        String contextId,
        java.util.List<String> referenceTaskIds,
        Map<String, Object> metadata
) {
    public TaskSendParams(String messageText, String model) {
        this(messageText, model, null, null, null, null, null);
    }

    public MessageSendParams toMessageSendParams() {
        return MessageSendParams.of(
                messageText == null ? "" : messageText,
                model
        );
    }

    public static TaskSendParams from(MessageSendParams msg) {
        if (msg == null || msg.message() == null) {
            return new TaskSendParams("", null);
        }
        String text = msg.message().parts().stream()
                .filter(p -> p instanceof TextPart)
                .map(p -> ((TextPart) p).text())
                .findFirst()
                .orElse("");
        String model = msg.configuration() == null ? null : (String) msg.configuration().get("model");
        return new TaskSendParams(text, model);
    }
}

