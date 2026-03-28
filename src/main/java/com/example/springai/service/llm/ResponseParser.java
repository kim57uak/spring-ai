package com.example.springai.service.llm;

/**
 * LLM 응답 파싱을 추상화한 인터페이스
 * SRP(Single Responsibility Principle) 준수 - 파싱 책임만 담당
 */
public interface ResponseParser {

    /**
     * 동기 응답에서 텍스트 추출
     */
    String extractText(String response);

    /**
     * 스트리밍 청크에서 텍스트 추출
     */
    String extractStreamText(String chunk);
}
