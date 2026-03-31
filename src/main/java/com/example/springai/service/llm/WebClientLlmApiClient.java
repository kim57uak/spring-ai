package com.example.springai.service.llm;

import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.util.Map;

/**
 * WebClient 기반 LLM API 클라이언트 구현체
 */
@Component
public class WebClientLlmApiClient implements LlmApiClient {

    private final WebClient webClient;

    public WebClientLlmApiClient(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.build();
    }

    @Override
    public String post(String url, Map<String, String> headers, String body) {
        WebClient.RequestBodySpec request = webClient.post().uri(url);

        headers.forEach(request::header);

        String response = request
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .block();
        if (response == null) {
            throw new IllegalStateException("LLM API returned empty response body");
        }
        return response;
    }

    @Override
    public Flux<String> streamPost(String url, Map<String, String> headers, String body) {
        WebClient.RequestBodySpec request = webClient.post().uri(url);

        headers.forEach(request::header);

        // 공급자가 전송하는 스트림 청크를 String Flux로 그대로 노출한다.
        // 실제 청크 포맷(JSON line/SSE 유사 등)은 상위 ResponseParser가 해석한다.
        return request
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(String.class);
    }
}
