package com.example.springsupervisorai.service.agent.store;

import java.util.List;

/**
 * 대화 메시지 히스토리 저장소 포트.
 * <p>
 * 구현체는 Supervisor 그래프 실행 간 대화 연속성을 위해
 * 세션별 정렬된 메시지 목록을 관리한다.
 */
public interface ConversationStore {

    /**
     * 주어진 세션의 메시지 히스토리를 로드한다.
     *
     * @param sessionId 세션 식별자
     * @return 정렬된 메시지 문자열 목록 (비어 있을 수 있음)
     */
    List<String> load(String sessionId);

    /**
     * 주어진 세션의 메시지를 저장하거나 추가한다.
     *
     * @param sessionId 세션 식별자
     * @param messages 영속화할 메시지
     */
    void save(String sessionId, List<String> messages);

    /**
     * 주어진 세션의 모든 메시지를 지운다.
     *
     * @param sessionId 세션 식별자
     */
    void clear(String sessionId);
}

