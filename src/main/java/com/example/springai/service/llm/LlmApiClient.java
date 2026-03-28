package com.example.springai.service.llm;

import reactor.core.publisher.Flux;

import java.util.Map;

/**
 * LLM API 호출을 추상화한 인터페이스
 * DIP(Dependency Inversion Principle) 준수를 위한 추상화 계층
 */
public interface LlmApiClient {

    /**
     * 동기 POST 요청
     */
    String post(String url, Map<String, String> headers, String body);

    /**
     * 스트리밍 POST 요청
     */
    Flux<String> streamPost(String url, Map<String, String> headers, String body);
}
