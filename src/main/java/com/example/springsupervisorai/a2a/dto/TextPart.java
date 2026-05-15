package com.example.springsupervisorai.a2a.dto;

/**
 * 텍스트를 표현하는 message part.
 */
public record TextPart(String text) implements Part {
    public TextPart {
        if (text == null) {
            throw new IllegalArgumentException("text must not be null");
        }
    }
}
