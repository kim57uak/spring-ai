package com.example.springsupervisorai.a2a.dto;

public record TextPart(String text) implements Part {
    public TextPart {
        if (text == null) {
            throw new IllegalArgumentException("text must not be null");
        }
    }
}
