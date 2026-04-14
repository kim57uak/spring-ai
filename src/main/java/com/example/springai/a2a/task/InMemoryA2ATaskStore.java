package com.example.springai.a2a.task;

import com.example.springai.model.agent.AgentScopeName;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * 로컬 실행 및 테스트용 {@link A2ATaskStore} 인메모리 구현체.
 * <p>
 * Redis 비활성화 시 task 스냅샷을 프로세스 메모리에 저장한다.
 */
@Component
@ConditionalOnProperty(name = "app.redis.enabled", havingValue = "false", matchIfMissing = true)
public class InMemoryA2ATaskStore implements A2ATaskStore {

    private final ConcurrentMap<String, A2aTaskSnapshot> tasks = new ConcurrentHashMap<>();

    /**
     * 새로운 task를 생성하고 SUBMITTED 상태로 저장한다.
     *
     * @param scopeName 스코프명
     * @param sessionId 세션 식별자
     * @param requestMessage 요청 메시지
     * @return 생성된 task 스냅샷
     */
    @Override
    public A2aTaskSnapshot create(AgentScopeName scopeName, String sessionId, String requestMessage) {
        Instant now = Instant.now();
        String taskId = "task-" + UUID.randomUUID();
        A2aTaskSnapshot snapshot = new A2aTaskSnapshot(
                taskId,
                scopeName,
                sessionId,
                A2aTaskStatus.SUBMITTED,
                now,
                now,
                requestMessage == null ? "" : requestMessage,
                "",
                "",
                ""
        );
        tasks.put(taskId, snapshot);
        return snapshot;
    }

    /**
     * taskId + scope 기준 단건 task를 조회한다.
     *
     * @param taskId task 식별자
     * @param scopeName 스코프명
     * @return task 스냅샷
     */
    @Override
    public Optional<A2aTaskSnapshot> get(String taskId, AgentScopeName scopeName) {
        A2aTaskSnapshot snapshot = tasks.get(taskId);
        if (snapshot == null || snapshot.scopeName() != scopeName) {
            return Optional.empty();
        }
        return Optional.of(snapshot);
    }

    /**
     * scope별 최신순 task 목록을 조회한다.
     *
     * @param scopeName 스코프명
     * @param limit 최대 조회 건수(1~200 범위로 보정)
     * @return task 목록
     */
    @Override
    public List<A2aTaskSnapshot> list(AgentScopeName scopeName, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        return tasks.values().stream()
                .filter(task -> task.scopeName() == scopeName)
                .sorted(Comparator.comparing(A2aTaskSnapshot::updatedAt).reversed())
                .limit(safeLimit)
                .toList();
    }

    /**
     * task 상태를 RUNNING으로 전이한다.
     *
     * @param taskId task 식별자
     * @param scopeName 스코프명
     * @return 상태 전이 후 스냅샷(미존재 또는 scope 불일치 시 empty)
     */
    @Override
    public Optional<A2aTaskSnapshot> markRunning(String taskId, AgentScopeName scopeName) {
        return update(taskId, scopeName, old -> A2aTaskSnapshotTransitions.markRunning(old, Instant.now()));
    }

    /**
     * task 상태를 COMPLETED로 전이한다.
     *
     * @param taskId task 식별자
     * @param scopeName 스코프명
     * @param responsePayload 응답 payload
     * @return 상태 전이 후 스냅샷(미존재 또는 scope 불일치 시 empty)
     */
    @Override
    public Optional<A2aTaskSnapshot> markCompleted(String taskId, AgentScopeName scopeName, String responsePayload) {
        return update(taskId, scopeName, old -> A2aTaskSnapshotTransitions.markCompleted(old, responsePayload, Instant.now()));
    }

    /**
     * task 상태를 FAILED로 전이한다.
     *
     * @param taskId task 식별자
     * @param scopeName 스코프명
     * @param errorCode 에러 코드
     * @param errorMessage 에러 메시지
     * @return 상태 전이 후 스냅샷(미존재 또는 scope 불일치 시 empty)
     */
    @Override
    public Optional<A2aTaskSnapshot> markFailed(
            String taskId,
            AgentScopeName scopeName,
            String errorCode,
            String errorMessage
    ) {
        return update(taskId, scopeName, old -> A2aTaskSnapshotTransitions.markFailed(old, errorCode, errorMessage, Instant.now()));
    }

    /**
     * task 상태를 CANCELED로 전이한다.
     *
     * @param taskId task 식별자
     * @param scopeName 스코프명
     * @param reason 취소 사유
     * @return 상태 전이 후 스냅샷(미존재 또는 scope 불일치 시 empty)
     */
    @Override
    public Optional<A2aTaskSnapshot> cancel(String taskId, AgentScopeName scopeName, String reason) {
        return update(taskId, scopeName, old -> A2aTaskSnapshotTransitions.cancel(old, reason, Instant.now()));
    }

    /**
     * 원자적 상태 전이 업데이트(scope 한정).
     * <p>
     * get->put 방식의 레이스를 완화하기 위해 compute를 사용하고,
     * scope 불일치 항목은 그대로 유지해 잘못된 상태 변이를 방지한다.
     *
     * @param taskId task 식별자
     * @param scopeName 스코프명
     * @param updater 현재 스냅샷을 다음 스냅샷으로 변환하는 함수
     * @return 전이된 스냅샷(전이 불가 시 empty)
     */
    private Optional<A2aTaskSnapshot> update(
            String taskId,
            AgentScopeName scopeName,
            Function<A2aTaskSnapshot, A2aTaskSnapshot> updater
    ) {
        AtomicReference<A2aTaskSnapshot> updatedRef = new AtomicReference<>();
        tasks.compute(taskId, (key, current) -> {
            if (current == null || current.scopeName() != scopeName) {
                return current;
            }
            A2aTaskSnapshot next = updater.apply(current);
            updatedRef.set(next);
            return next;
        });
        return Optional.ofNullable(updatedRef.get());
    }
}
