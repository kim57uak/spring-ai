package com.example.springai.a2a.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

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
