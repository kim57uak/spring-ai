package com.example.springai.service.chat;

import reactor.core.publisher.Flux;

/**
 * 스트리밍 채팅 서비스 인터페이스
 * ISP(Interface Segregation Principle) 준수 - 스트리밍만 필요한 클라이언트를 위한 인터페이스
 */
public interface StreamChatService extends ChatService {

    /**
     * 스트리밍 방식으로 응답 생성
     */
    Flux<String> streamGenerate(String message);

    default Flux<String> streamGenerate(String message, ChatRequestContext context) {
        return streamGenerate(message);
    }
}
