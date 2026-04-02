package com.example.springai.service.chat;

import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
