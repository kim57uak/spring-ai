package com.example.springai.service;

import com.example.springai.service.chat.AbstractLlmChatService;
import com.example.springai.service.chat.LlmCallPolicy;
import com.example.springai.service.llm.LlmApiClient;
import com.example.springai.service.llm.LlmCredentialValidator;
import com.example.springai.service.parser.GeminiResponseParser;
import com.example.springai.service.util.JsonUtils;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
@ConditionalOnExpression("'${http-llm.gemma.api-key:}' != ''")
public class GemmaModelChatService extends AbstractLlmChatService {

    @Value("${http-llm.gemma.api-key:}")
    private String apiKey;

    @Value("${http-llm.gemma.model}")
    private String model;

    @Value("${http-llm.gemma.base-url}")
    private String baseUrl;

    @Value("${http-llm.gemma.max-tokens:16384}")
    private int maxTokens;

    public GemmaModelChatService(
            LlmApiClient apiClient,
            GeminiResponseParser responseParser,
            LlmCallPolicy callPolicy
    ) {
        super(apiClient, responseParser, callPolicy);
    }

    @PostConstruct
    void validateConfiguration() {
        LlmCredentialValidator.requireValidApiKey(
                "gemma",
                apiKey,
                "GEMMA_API_KEY",
                "HTTP_GEMINI_API_KEY"
        );
    }

    @Override
    public ChatModelType modelType() {
        return ChatModelType.GEMMA;
    }

    @Override
    protected String buildUrl(boolean streaming) {
        // Gemini 호환 API를 사용하므로 endpoint 규칙도 Gemini와 동일하다.
        String endpoint = streaming ? "streamGenerateContent" : "generateContent";
        return String.format("%s/%s:%s?key=%s", baseUrl, model, endpoint, apiKey);
    }

    @Override
    protected Map<String, String> buildHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        return headers;
    }

    @Override
    protected String buildRequestBody(String message, boolean streaming) {
        // 요청 바디는 동일하고, 스트리밍 여부는 endpoint 선택으로 결정된다.
        return String.format(
                "{\"contents\":[{\"parts\":[{\"text\":%s}]}],\"generationConfig\":{\"maxOutputTokens\":%d}}",
                JsonUtils.escapeJson(message),
                maxTokens
        );
    }
}
