package com.example.springai.service.agent.store;

import java.util.Optional;

/**
 * 그래프 실행 체크포인트 저장소 계약.
 * <p>
 * 세션 재개를 위한 체크포인트 로드/저장/삭제를 정의한다.
 */
public interface GraphCheckpointStore {

    /**
     * 세션의 마지막 체크포인트를 조회한다.
     */
    Optional<String> loadCheckpoint(String sessionId);

    /**
     * 세션의 체크포인트를 저장한다.
     */
    void saveCheckpoint(String sessionId, String payload);

    /**
     * 세션의 체크포인트를 삭제한다.
     */
    void clear(String sessionId);
}
