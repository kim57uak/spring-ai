package com.example.springsupervisorai.service.agent.store;

import java.util.Optional;

/**
 * Supervisor 그래프 실행 체크포인트 저장소 포트.
 * <p>
 * 세션별로 직렬화된 그래프 상태를 영속화하여
 * 장애 발생 시 Supervisor 상태 머신의 재개를 가능하게 한다.
 */
public interface GraphCheckpointStore {

    /**
     * 세션의 가장 최근 체크포인트를 로드한다.
     *
     * @param sessionId 세션 식별자
     * @return 직렬화된 체크포인트 페이로드 (없으면 empty)
     */
    Optional<String> loadCheckpoint(String sessionId);

    /**
     * 세션의 체크포인트를 영속화한다.
     *
     * @param sessionId 세션 식별자
     * @param payload 영속화할 직렬화된 그래프 상태
     */
    void saveCheckpoint(String sessionId, String payload);

    /**
     * 주어진 세션의 체크포인트를 제거한다.
     *
     * @param sessionId 세션 식별자
     */
    void clear(String sessionId);
}

