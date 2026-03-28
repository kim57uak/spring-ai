package com.example.springai.service.llm;

public final class LlmCredentialValidator {

    private LlmCredentialValidator() {
    }

    public static void requireValidApiKey(String provider, String apiKey, String... expectedEnvVars) {
        String normalized = apiKey == null ? "" : apiKey.trim();
        if (normalized.isEmpty()) {
            throw new IllegalStateException(buildMessage(provider, "API key is missing", expectedEnvVars));
        }
        if (normalized.equalsIgnoreCase("null") || normalized.equalsIgnoreCase("undefined")) {
            throw new IllegalStateException(buildMessage(provider, "API key has an invalid placeholder value", expectedEnvVars));
        }
        if (normalized.startsWith("${") && normalized.endsWith("}")) {
            throw new IllegalStateException(buildMessage(provider, "API key placeholder was not resolved", expectedEnvVars));
        }
    }

    private static String buildMessage(String provider, String reason, String... expectedEnvVars) {
        String expected = expectedEnvVars == null || expectedEnvVars.length == 0
                ? "relevant provider env var"
                : String.join(", ", expectedEnvVars);
        return String.format("%s configuration error: %s. Set one of: %s", provider, reason, expected);
    }
}
