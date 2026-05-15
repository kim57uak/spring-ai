package com.example.springsupervisorai.service.agent.store;

import com.example.springsupervisorai.model.SwarmState;
import com.example.springsupervisorai.service.agent.swarm.SwarmStateVersionConflictException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 메모리 기반 Swarm state 저장소 구현.
 * <p>
 * 동시성 안전성:
 * - taskId별 lock을 통해 read-merge-write atomic 보장
 * - 낙관적 락(optimistic locking): stateVersion 기반 충돌 감지
 * - TTL 기반 자동 정리로 메모리 누수 방지
 * <p>
 * 활성화 조건:
 * - app.redis.enabled=false 또는 미설정 시 (기본값)
 * - 단일 인스턴스 환경에서만 사용 권장
 * - 분산 환경에서는 {@link RedisSupervisorSwarmStateStore} 사용
 */
@Component
@ConditionalOnProperty(name = "app.redis.enabled", havingValue = "false", matchIfMissing = true)
public class InMemorySupervisorSwarmStateStore implements SupervisorSwarmStateStore {

    private static final long MAX_TTL_MS = 3_600_000L; // 1시간
    private static final int MAX_ENTRIES_PER_MAP = 10_000;

    private final ConcurrentMap<String, SwarmState> states = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, SwarmState> latestBySession = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Lock> locks = new ConcurrentHashMap<>();

    /**
     * {@inheritDoc}
     */
    @Override
    public Optional<SwarmState> load(String taskId) {
        evictExpiredEntries();
        return Optional.ofNullable(states.get(taskId));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Optional<SwarmState> loadLatestBySession(String sessionId) {
        evictExpiredEntries();
        return Optional.ofNullable(latestBySession.get(sessionId));
    }

    /**
     * {@inheritDoc}
     * <p>
     * 낙관적 락 구현:
     * - stateVersion=0: 신규 생성, 무조건 저장
     * - stateVersion>0: 현재 저장소 버전과 비교하여 (expectedVersion - 1) 일치 시에만 저장
     * - 불일치 시 SwarmStateVersionConflictException 발생
     */
    @Override
    public SwarmState upsert(SwarmState state) throws SwarmStateVersionConflictException {
        String taskId = state.taskId();
        Lock lock = locks.computeIfAbsent(taskId, k -> new ReentrantLock());
        lock.lock();
        try {
            // 낙관적 락 검증
            SwarmState current = states.get(taskId);
            long expectedPreviousVersion = state.stateVersion() - 1;

            if (state.stateVersion() > 0 && current != null) {
                // 기존 상태가 있고 버전이 0보다 크면 충돌 검사
                if (current.stateVersion() != expectedPreviousVersion) {
                    throw new SwarmStateVersionConflictException(
                            taskId,
                            expectedPreviousVersion,
                            current.stateVersion()
                    );
                }
            }

            // 저장
            states.put(taskId, state);
            if (state.sessionId() != null && !state.sessionId().isBlank()) {
                latestBySession.put(state.sessionId(), state);
            }
            evictExpiredEntries();
            return state;
        } finally {
            lock.unlock();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void clearSession(String sessionId) {
        SwarmState removed = latestBySession.remove(sessionId);
        if (removed != null) {
            states.remove(removed.taskId());
        }
    }

    /**
     * 만료된 항목을 정리한다.
     * <p>
     * - 1시간 이상 지난 항목 제거
     * - 맵 크기가 10,000 초과 시 가장 오래된 20% 제거
     */
    private void evictExpiredEntries() {
        Instant now = Instant.now();
        Instant threshold = now.minusMillis(MAX_TTL_MS);

        // TTL 기반 제거
        states.entrySet().removeIf(entry -> entry.getValue().updatedAt().isBefore(threshold));
        latestBySession.entrySet().removeIf(entry -> entry.getValue().updatedAt().isBefore(threshold));

        // 크기 기반 제거 (states)
        if (states.size() > MAX_ENTRIES_PER_MAP) {
            states.entrySet().stream()
                    .sorted(Map.Entry.comparingByValue((a, b) -> a.updatedAt().compareTo(b.updatedAt())))
                    .limit(states.size() / 5) // 20% 제거
                    .map(Map.Entry::getKey)
                    .forEach(states::remove);
        }

        // 크기 기반 제거 (latestBySession)
        if (latestBySession.size() > MAX_ENTRIES_PER_MAP) {
            latestBySession.entrySet().stream()
                    .sorted(Map.Entry.comparingByValue((a, b) -> a.updatedAt().compareTo(b.updatedAt())))
                    .limit(latestBySession.size() / 5)
                    .map(Map.Entry::getKey)
                    .forEach(latestBySession::remove);
        }

        // 사용되지 않는 lock 정리
        locks.entrySet().removeIf(entry -> !states.containsKey(entry.getKey()));
    }
}
