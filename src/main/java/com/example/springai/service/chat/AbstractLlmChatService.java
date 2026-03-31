package com.example.springai.service.chat;

import com.example.springai.service.llm.LlmApiClient;
import com.example.springai.service.llm.ResponseParser;
import com.example.springai.exception.ChatProcessingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import java.util.Map;

/**
 * LLM 채팅 서비스의 추상 기본 클래스
 * DRY(Don't Repeat Yourself) 원칙 준수 - 공통 로직 제공
 * Template Method 패턴 적용
 */
public abstract class AbstractLlmChatService implements SyncChatService, StreamChatService {

    private static final Logger logger = LoggerFactory.getLogger(AbstractLlmChatService.class);

    protected final LlmApiClient apiClient;
    protected final ResponseParser responseParser;
    protected final LlmCallPolicy callPolicy;

    protected AbstractLlmChatService(
            LlmApiClient apiClient,
            ResponseParser responseParser,
            LlmCallPolicy callPolicy
    ) {
        this.apiClient = apiClient;
        this.responseParser = responseParser;
        this.callPolicy = callPolicy;
    }

    @Override
    public String generate(String message) {
        String url = buildUrl(false);
        Map<String, String> headers = buildHeaders();
        String body = buildRequestBody(message, false);
        return callPolicy.executeSync(modelType().value(), () -> {
            String response = apiClient.post(url, headers, body);
            return responseParser.extractText(response);
        });
    }

    @Override
    public Flux<String> streamGenerate(String message) {
        // 스트림 시작 전 공통 rate-limit 정책 적용.
        callPolicy.acquireBeforeStream();
        String url = buildUrl(true);
        Map<String, String> headers = buildHeaders();
        String body = buildRequestBody(message, true);

        // 공급자 API의 청크 응답을 Flux로 받아 파싱 후 상위 계층으로 전달한다.
        return apiClient.streamPost(url, headers, body)
                .retryWhen(callPolicy.streamRetrySpec(modelType().value()))
                .map(responseParser::extractStreamText)
                .onErrorMap(
                        WebClientResponseException.class,
                        e -> callPolicy.toChatProcessingException(modelType().value(), e)
                )
                .onErrorMap(e -> !(e instanceof ChatProcessingException), this::toUnexpectedChatProcessingException)
                .doOnError(e -> logger.error("{} stream error", modelType().value(), e));
    }

    /**
     * API URL 생성 (하위 클래스에서 구현)
     */
    protected abstract String buildUrl(boolean streaming);

    /**
     * 요청 헤더 생성 (하위 클래스에서 구현)
     */
    protected abstract Map<String, String> buildHeaders();

    /**
     * 요청 바디 생성 (하위 클래스에서 구현)
     */
    protected abstract String buildRequestBody(String message, boolean streaming);

    private ChatProcessingException toUnexpectedChatProcessingException(Throwable throwable) {
        return new ChatProcessingException("API call failed: " + throwable.getMessage(), throwable);
    }
}
