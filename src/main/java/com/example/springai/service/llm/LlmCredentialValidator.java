package com.example.springai.service.llm;

/**
 * LLM 공급사 API 키 유효성 검증 유틸리티.
 * <p>
 * 검증 목적:
 * - 누락된 키를 조기에 탐지한다.
 * - placeholder 값(null/undefined/${...}) 오입력을 차단한다.
 */
public final class LlmCredentialValidator {

    private LlmCredentialValidator() {
    }

    /**
     * API 키 유효성을 검사한다.
     * <p>
     * 검사 항목:
     * - 빈 문자열 여부
     * - placeholder 문자열 여부
     * - 프로퍼티 치환 미해결 여부
     */
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

    /**
     * 구성 오류 메시지를 생성한다.
     * <p>
     * 메시지 구성:
     * - 공급사 이름
     * - 실패 원인
     * - 설정해야 할 환경변수 힌트
     */
    private static String buildMessage(String provider, String reason, String... expectedEnvVars) {
        String expected = expectedEnvVars == null || expectedEnvVars.length == 0
                ? "relevant provider env var"
                : String.join(", ", expectedEnvVars);
        return String.format("%s configuration error: %s. Set one of: %s", provider, reason, expected);
    }
}
