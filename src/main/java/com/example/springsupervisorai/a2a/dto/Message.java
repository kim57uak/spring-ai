package com.example.springsupervisorai.a2a.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * A2A 메시지. role(발신자 역할)과 parts(메시지 내용 목록)로 구성된다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Message(
        String role,
        List<Part> parts
) {
    public Message {
        if (role == null) {
            role = "user";
        }
        if (parts == null) {
            parts = List.of();
        }
    }

    public static Message of(String text) {
        return new Message("user", List.of(new TextPart(text)));
    }

    public static Message assistant(String text) {
        return new Message("assistant", List.of(new TextPart(text)));
    }
}
