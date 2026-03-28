package com.example.springai.service;

public enum ChatModelType {
    GEMINI("gemini"),
    GEMMA("gemma"),
    MISTRAL("mistral"),
    OPENAI("openai");

    private static final ChatModelType DEFAULT_MODEL = MISTRAL;
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

        String normalized = rawModel.trim().toLowerCase();
        if (normalized.startsWith("gpt")) {
            return OPENAI;
        }
        if (normalized.startsWith("gemma")) {
            return GEMMA;
        }
        for (ChatModelType modelType : values()) {
            if (modelType.value.equals(normalized)) {
                return modelType;
            }
        }
        throw new IllegalArgumentException("Unsupported model: " + rawModel);
    }
}
