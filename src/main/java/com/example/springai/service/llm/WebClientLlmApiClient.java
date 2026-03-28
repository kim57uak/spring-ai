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

        return request
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(String.class);
    }
}
