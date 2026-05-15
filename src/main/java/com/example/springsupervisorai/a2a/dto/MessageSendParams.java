package com.example.springsupervisorai.a2a.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * A2A message/send 요청 파라미터.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MessageSendParams(
        Message message,
        Map<String, Object> configuration,
        Map<String, Object> metadata
) {
    public MessageSendParams(Message message) {
        this(message, null, null);
    }

    public static MessageSendParams of(String text) {
        return new MessageSendParams(Message.of(text));
    }

    public static MessageSendParams of(String text, String model) {
        return new MessageSendParams(
                Message.of(text),
                model == null ? null : Map.of("model", model),
                null
        );
    }
}
