package com.example.springai.service;

import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public enum ChatModelType {
    GEMINI("gemini"),
    GEMMA("gemma"),
    MISTRAL("mistral"),
    OPENAI("openai");

    private static final ChatModelType DEFAULT_MODEL = MISTRAL;
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
        if (normalized.startsWith("gpt")) {
            return OPENAI;
        }
        if (normalized.startsWith("gemma")) {
            return GEMMA;
        }
        ChatModelType direct = LOOKUP.get(normalized);
        if (direct != null) {
            return direct;
        }
        throw new IllegalArgumentException("Unsupported model: " + rawModel);
    }
}
