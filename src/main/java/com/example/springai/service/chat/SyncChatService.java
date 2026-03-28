package com.example.springai.service.chat;

/**
 * 동기 채팅 서비스 인터페이스
 * ISP(Interface Segregation Principle) 준수 - 동기 호출만 필요한 클라이언트를 위한 인터페이스
 */
public interface SyncChatService extends ChatService {

    /**
     * 동기 방식으로 응답 생성
     */
    String generate(String message);
}
