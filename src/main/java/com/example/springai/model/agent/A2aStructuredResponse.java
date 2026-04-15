package com.example.springai.model.agent;

import java.util.Map;

public record A2aStructuredResponse(
        String response,
        Map<String, Object> structuredData
) {

    public static A2aStructuredResponse of(String response, Map<String, Object> structuredData) {
        return new A2aStructuredResponse(response == null ? "" : response, structuredData == null ? Map.of() : Map.copyOf(structuredData));
    }
}
