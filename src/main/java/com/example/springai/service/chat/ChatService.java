package com.example.springai.service.chat;

/**
 * 채팅 모델의 기본 인터페이스
 * ISP(Interface Segregation Principle) 준수 - 최소한의 공통 책임만 정의
 */
public interface ChatService {

    /**
     * 지원하는 모델 타입 반환
     */
    ChatModelType modelType();
}
