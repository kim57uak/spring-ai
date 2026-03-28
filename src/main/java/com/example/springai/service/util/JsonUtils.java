package com.example.springai.service.util;

/**
 * JSON 관련 유틸리티 클래스
 * DRY(Don't Repeat Yourself) 원칙 준수
 */
public final class JsonUtils {

    private JsonUtils() {
        throw new AssertionError("Utility class should not be instantiated");
    }

    /**
     * JSON 문자열 이스케이프 처리
     */
    public static String escapeJson(String text) {
        if (text == null) {
            return "\"\"";
        }
        return "\"" + text
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t") + "\"";
    }

    /**
     * JSON 문자열 언이스케이프 처리
     */
    public static String unescapeJson(String text) {
        if (text == null) {
            return "";
        }
        return text
                .replace("\\n", "\n")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\r", "\r")
                .replace("\\t", "\t");
    }
}
