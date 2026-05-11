package com.example.springsupervisorai.a2a.task;

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
 * Redis 비활성화 환경에서 Supervisor task를 저장하는 인메모리 구현체.
 */
@Component("supervisorInMemoryA2ATaskStore")
@ConditionalOnProperty(name = "app.redis.enabled", havingValue = "false", matchIfMissing = true)
public class InMemoryA2ATaskStore implements A2ATaskStore {

    private final ConcurrentMap<String, A2aTaskSnapshot> tasks = new ConcurrentHashMap<>();

    /**
     * 새로운 task를 생성하고 SUBMITTED 상태로 저장한다.
     *
     * @param sessionId 세션 식별자
     * @param requestMessage 요청 메시지
     * @return 생성된 task 스냅샷
     */
    @Override
    public A2aTaskSnapshot create(String sessionId, String requestMessage) {
        Instant now = Instant.now();
        String taskId = "sup-task-" + UUID.randomUUID();
        A2aTaskSnapshot snapshot = new A2aTaskSnapshot(
                taskId,
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
     * taskId 기준 단건 task를 조회한다.
     *
     * @param taskId task 식별자
     * @return task 스냅샷
     */
    @Override
    public Optional<A2aTaskSnapshot> get(String taskId) {
        return Optional.ofNullable(tasks.get(taskId));
    }

    /**
     * 최신순 task 목록을 조회한다.
     *
     * @param limit 최대 조회 건수(1~200 범위로 보정)
     * @return task 목록
     */
    @Override
    public List<A2aTaskSnapshot> list(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        return tasks.values().stream()
                .sorted(Comparator.comparing(A2aTaskSnapshot::updatedAt).reversed())
                .limit(safeLimit)
                .toList();
    }

    /**
     * task 상태를 RUNNING으로 전이한다.
     *
     * @param taskId task 식별자
     * @return 상태 전이 후 스냅샷(미존재 시 empty)
     */
    @Override
    public Optional<A2aTaskSnapshot> markRunning(String taskId) {
        return update(taskId, old -> A2aTaskSnapshotTransitions.markRunning(old, Instant.now()));
    }

    /**
     * {@inheritDoc}
     *
     * @param taskId task 식별자
     * @param reason 대기 사유
     * @return 상태 전이 후 스냅샷(미존재 시 empty)
     */
    @Override
    public Optional<A2aTaskSnapshot> markWaitingReview(String taskId, String reason) {
        return update(taskId, old -> A2aTaskSnapshotTransitions.markWaitingReview(old, reason, Instant.now()));
    }

    /**
     * task 상태를 COMPLETED로 전이한다.
     *
     * @param taskId task 식별자
     * @param responsePayload 응답 payload
     * @return 상태 전이 후 스냅샷(미존재 시 empty)
     */
    @Override
    public Optional<A2aTaskSnapshot> markCompleted(String taskId, String responsePayload) {
        return update(taskId, old -> A2aTaskSnapshotTransitions.markCompleted(old, responsePayload, Instant.now()));
    }

    /**
     * task 상태를 FAILED로 전이한다.
     *
     * @param taskId task 식별자
     * @param errorCode 에러 코드
     * @param errorMessage 에러 메시지
     * @return 상태 전이 후 스냅샷(미존재 시 empty)
     */
    @Override
    public Optional<A2aTaskSnapshot> markFailed(String taskId, String errorCode, String errorMessage) {
        return update(taskId, old -> A2aTaskSnapshotTransitions.markFailed(old, errorCode, errorMessage, Instant.now()));
    }

    /**
     * task 상태를 CANCELED로 전이한다.
     *
     * @param taskId task 식별자
     * @param reason 취소 사유
     * @return 상태 전이 후 스냅샷(미존재 시 empty)
     */
    @Override
    public Optional<A2aTaskSnapshot> cancel(String taskId, String reason) {
        return update(taskId, old -> A2aTaskSnapshotTransitions.cancel(old, reason, Instant.now()));
    }

    /**
     * task의 요청 메시지를 갱신한다.
     *
     * @param taskId task 식별자
     * @param newMessage 새로운 요청 메시지
     * @return 갱신된 스냅샷(미존재 시 empty)
     */
    @Override
    public Optional<A2aTaskSnapshot> updateTaskMessage(String taskId, String newMessage) {
        return update(taskId, old -> A2aTaskSnapshotTransitions.updateTaskMessage(old, newMessage, Instant.now()));
    }

    /**
     * 원자적 상태 전이 업데이트.
     * <p>
     * 기존 get->put 패턴의 경쟁 상태를 줄이기 위해 ConcurrentMap.compute를 사용한다.
     * 동시 갱신 시 마지막 write 유실/역전 가능성을 최소화하는 목적이다.
     *
     * @param taskId task 식별자
     * @param updater 현재 스냅샷을 다음 스냅샷으로 변환하는 함수
     * @return 전이된 스냅샷(전이 불가 시 empty)
     */
    private Optional<A2aTaskSnapshot> update(String taskId, Function<A2aTaskSnapshot, A2aTaskSnapshot> updater) {
        AtomicReference<A2aTaskSnapshot> updatedRef = new AtomicReference<>();
        tasks.compute(taskId, (key, current) -> {
            if (current == null) {
                return null;
            }
            A2aTaskSnapshot updated = updater.apply(current);
            updatedRef.set(updated);
            return updated;
        });
        return Optional.ofNullable(updatedRef.get());
    }
}
