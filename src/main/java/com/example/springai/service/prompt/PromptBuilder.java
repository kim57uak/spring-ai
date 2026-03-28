package com.example.springai.service.prompt;

import com.example.springai.service.memory.Message;

import java.util.List;

/**
 * 프롬프트 생성을 담당하는 인터페이스
 * SRP(Single Responsibility Principle) 준수
 */
public interface PromptBuilder {

    /**
     * 대화 히스토리를 기반으로 프롬프트 생성
     */
    String buildPrompt(List<Message> history, String currentMessage);
}
