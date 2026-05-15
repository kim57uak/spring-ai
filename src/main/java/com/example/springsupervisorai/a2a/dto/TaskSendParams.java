package com.example.springsupervisorai.a2a.dto;

import java.util.Map;

/**
 * A2A task 전송 요청 파라미터. 에이전트에 메시지를 전송하여 새 task를 시작한다.
 */
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
        // parts 중 첫 번째 TextPart에서 메시지 텍스트 추출
        if (msg == null || msg.message() == null) {
            return new TaskSendParams("", null);
        }
        // configuration에서 model 추출
        String text = msg.message().parts().stream()
                .filter(p -> p instanceof TextPart)
                .map(p -> ((TextPart) p).text())
                .findFirst()
                .orElse("");
        String model = msg.configuration() == null ? null : (String) msg.configuration().get("model");
        return new TaskSendParams(text, model);
    }
}

