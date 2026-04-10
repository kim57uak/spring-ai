package com.example.springai.service.chat;

/**
 * 구조화 응답(entity) 생성 기능을 제공하는 채팅 서비스 인터페이스.
 * <p>
 * 모델 응답을 지정 타입으로 역직렬화해 반환한다.
 */
public interface StructuredChatService extends ChatService {

    /**
     * 텍스트 입력으로부터 지정 타입의 구조화 응답을 생성한다.
     * <p>
     * 구현체는 타입 변환 실패 시 예외를 발생시킬 수 있다.
     */
    <T> T generateStructured(String message, Class<T> type);

    /**
     * 요청 문맥을 포함해 지정 타입의 구조화 응답을 생성한다.
     */
    default <T> T generateStructured(String message, Class<T> type, ChatRequestContext context) {
        return generateStructured(message, type);
    }
}
