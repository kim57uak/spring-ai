package com.example.springai.service.chat;

import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 애플리케이션에서 지원하는 채팅 모델 타입 열거형.
 * <p>
 * 공급사 alias 문자열을 내부 표준 타입으로 매핑한다.
 */
public enum ChatModelType {
    GEMINI("gemini"),
    GEMINI_LITE("gemini-lite"),
    MISTRAL("mistral"),
    OPENAI("openai");

    private static final ChatModelType DEFAULT_MODEL = OPENAI;
    private static final Map<String, ChatModelType> LOOKUP = Stream.of(values())
            .collect(Collectors.toUnmodifiableMap(ChatModelType::value, Function.identity()));
    private final String value;

    ChatModelType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    /**
     * 사용자 입력 모델 문자열을 내부 모델 타입으로 정규화한다.
     * <p>
     * 정확 매칭 우선 후 접두어 기반 매핑을 수행한다.
     */
    public static ChatModelType from(String rawModel) {
        if (rawModel == null || rawModel.isBlank()) {
            return DEFAULT_MODEL;
        }

        String normalized = rawModel.trim().toLowerCase(Locale.ROOT);
        ChatModelType direct = LOOKUP.get(normalized);
        if (direct != null) {
            return direct;
        }
        if (normalized.startsWith("gpt")) {
            return OPENAI;
        }
        if (normalized.startsWith("gemini-2.5-flash-lite")) {
            return GEMINI_LITE;
        }
        if (normalized.startsWith("gemini")) {
            return GEMINI;
        }
        if (normalized.startsWith("mistral")) {
            return MISTRAL;
        }
        throw new IllegalArgumentException("Unsupported model: " + rawModel);
    }
}
