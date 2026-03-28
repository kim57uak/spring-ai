package com.example.springai.service;

import com.example.springai.service.chat.AbstractLlmChatService;
import com.example.springai.service.chat.LlmCallPolicy;
import com.example.springai.service.llm.LlmApiClient;
import com.example.springai.service.llm.LlmCredentialValidator;
import com.example.springai.service.parser.OpenAiResponseParser;
import com.example.springai.service.util.JsonUtils;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * OpenAI LLM 채팅 서비스
 * SOLID 원칙 준수:
 * - SRP: OpenAI API 호출만 담당
 * - OCP: 추상 클래스를 통해 확장 가능
 * - LSP: AbstractLlmChatService를 대체 가능
 * - DIP: 추상화(LlmApiClient, ResponseParser)에 의존
 */
@Service
public class OpenAiModelChatService extends AbstractLlmChatService {

    @Value("${http-llm.openai.api-key}")
    private String apiKey;

    @Value("${http-llm.openai.model}")
    private String model;

    @Value("${http-llm.openai.base-url}")
    private String baseUrl;

    @Value("${http-llm.openai.max-tokens:50000}")
    private int maxTokens;

    public OpenAiModelChatService(
            LlmApiClient apiClient,
            OpenAiResponseParser responseParser,
            LlmCallPolicy callPolicy
    ) {
        super(apiClient, responseParser, callPolicy);
    }

    @PostConstruct
    void validateConfiguration() {
        LlmCredentialValidator.requireValidApiKey(
                "openai",
                apiKey,
                "OPENAI_API_KEY",
                "HTTP_OPENAI_API_KEY",
                "OPEN_API_KEY"
        );
    }

    @Override
    public ChatModelType modelType() {
        return ChatModelType.OPENAI;
    }

    @Override
    protected String buildUrl(boolean streaming) {
        return baseUrl;
    }

    @Override
    protected Map<String, String> buildHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", "Bearer " + apiKey);
        return headers;
    }

    @Override
    protected String buildRequestBody(String message, boolean streaming) {
        String streamParam = streaming ? ",\"stream\":true" : "";
        return String.format(
                "{\"model\":\"%s\",\"messages\":[{\"role\":\"user\",\"content\":%s}],\"max_tokens\":%d%s}",
                model,
                JsonUtils.escapeJson(message),
                maxTokens,
                streamParam
        );
    }
}
