package com.example.springai.service.memory;

/**
 * 대화 메시지를 표현하는 불변 객체
 */
public record Message(String role, String content) {
}
