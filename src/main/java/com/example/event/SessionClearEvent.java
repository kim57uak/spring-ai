package com.example.event;

/**
 * 세션 초기화 이벤트.
 * 세션에 속한 모든 저장소의 데이터를 삭제해야 할 때 발행된다.
 */
public record SessionClearEvent(String sessionId) {
}
