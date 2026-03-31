package com.example.springai.service;

import com.example.springai.service.chat.AbstractLlmChatService;
import com.example.springai.service.chat.LlmCallPolicy;
import com.example.springai.service.llm.LlmApiClient;
import com.example.springai.service.llm.LlmCredentialValidator;
import com.example.springai.service.parser.GeminiResponseParser;
import com.example.springai.service.util.JsonUtils;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * Gemini LLM 채팅 서비스
 * SOLID 원칙 준수:
 * - SRP: Gemini API 호출만 담당
 * - OCP: 추상 클래스를 통해 확장 가능
 * - LSP: AbstractLlmChatService를 대체 가능
 * - DIP: 추상화(LlmApiClient, ResponseParser)에 의존
 */
@Service
public class GeminiModelChatService extends AbstractLlmChatService {

    @Value("${http-llm.gemini.api-key}")
    private String apiKey;

    @Value("${http-llm.gemini.model}")
    private String model;

    @Value("${http-llm.gemini.base-url}")
    private String baseUrl;

    @Value("${http-llm.gemini.max-tokens:50000}")
    private int maxTokens;

    public GeminiModelChatService(
            LlmApiClient apiClient,
            GeminiResponseParser responseParser,
            LlmCallPolicy callPolicy
    ) {
        super(apiClient, responseParser, callPolicy);
    }

    @PostConstruct
    void validateConfiguration() {
        LlmCredentialValidator.requireValidApiKey(
                "gemini",
                apiKey,
                "HTTP_GEMINI_API_KEY"
        );
    }

    @Override
    public ChatModelType modelType() {
        return ChatModelType.GEMINI;
    }

    @Override
    protected String buildUrl(boolean streaming) {
        // Gemini/Gemma 계열은 스트리밍 전용 endpoint가 분리되어 있다.
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
