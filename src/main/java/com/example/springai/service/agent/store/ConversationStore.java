package com.example.springai.service.agent.store;

import java.util.List;

/**
 * 세션별 대화 히스토리 저장소 계약.
 * <p>
 * 구현체는 Redis/메모리 등 저장 매체를 추상화한다.
 */
public interface ConversationStore {

    /**
     * 세션의 대화 메시지 목록을 조회한다.
     */
    List<String> load(String sessionId);

    /**
     * 세션의 대화 메시지 목록을 저장한다.
     */
    void save(String sessionId, List<String> messages);

    /**
     * 세션의 대화 히스토리를 삭제한다.
     */
    void clear(String sessionId);
}
