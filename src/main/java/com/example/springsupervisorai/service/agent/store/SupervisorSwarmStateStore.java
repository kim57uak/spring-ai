package com.example.springsupervisorai.service.agent.store;

import com.example.springsupervisorai.model.SwarmState;
import com.example.springsupervisorai.service.agent.swarm.SwarmStateVersionConflictException;

import java.util.Optional;

/**
 * Swarm shared state 저장소 포트.
 * <p>
 * 구현체는 다음을 제공해야 한다:
 * - taskId/sessionId 기반 조회
 * - 낙관적 락(optimistic locking): stateVersion 기반 충돌 감지
 * - 분산 환경 안전성: Redis 등 외부 저장소 사용 시 동시성 제어
 */
public interface SupervisorSwarmStateStore {

    /**
     * taskId로 swarm 상태를 조회한다.
     *
     * @param taskId 조회 task id
     * @return 상태(optional)
     */
    Optional<SwarmState> load(String taskId);

    /**
     * sessionId 기준 최신 swarm 상태를 조회한다.
     *
     * @param sessionId 조회 세션 id
     * @return 최신 상태(optional)
     */
    Optional<SwarmState> loadLatestBySession(String sessionId);

    /**
     * 세션에 속한 swarm 상태를 삭제한다.
     *
     * @param sessionId 세션 식별자
     */
    void clearSession(String sessionId);

    /**
     * swarm 상태를 저장/갱신한다.
     * <p>
     * 낙관적 락 정책:
     * - state.stateVersion()이 0이면 신규 생성으로 간주하고 무조건 저장
     * - state.stateVersion() > 0이면 저장소의 현재 버전과 비교하여 충돌 검증
     * - 버전 불일치 시 {@link SwarmStateVersionConflictException} 발생
     *
     * @param state 저장 상태
     * @return 저장된 상태
     * @throws SwarmStateVersionConflictException 버전 충돌 시
     */
    SwarmState upsert(SwarmState state) throws SwarmStateVersionConflictException;
}
