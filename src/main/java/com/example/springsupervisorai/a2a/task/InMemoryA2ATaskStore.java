package com.example.springsupervisorai.a2a.task;

import com.example.springsupervisorai.model.SupervisorErrorCode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

@Component("supervisorInMemoryA2ATaskStore")
@ConditionalOnProperty(name = "app.redis.enabled", havingValue = "false", matchIfMissing = true)
public class InMemoryA2ATaskStore implements A2ATaskStore {

    private final ConcurrentMap<String, A2aTaskSnapshot> tasks = new ConcurrentHashMap<>();

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

    @Override
    public Optional<A2aTaskSnapshot> get(String taskId) {
        return Optional.ofNullable(tasks.get(taskId));
    }

    @Override
    public List<A2aTaskSnapshot> list(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        return tasks.values().stream()
                .sorted(Comparator.comparing(A2aTaskSnapshot::updatedAt).reversed())
                .limit(safeLimit)
                .toList();
    }

    @Override
    public Optional<A2aTaskSnapshot> markRunning(String taskId) {
        return update(taskId, old -> new A2aTaskSnapshot(
                old.taskId(),
                old.sessionId(),
                A2aTaskStatus.RUNNING,
                old.createdAt(),
                Instant.now(),
                old.requestMessage(),
                old.responsePayload(),
                old.errorCode(),
                old.errorMessage()
        ));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Optional<A2aTaskSnapshot> markWaitingReview(String taskId, String reason) {
        return update(taskId, old -> new A2aTaskSnapshot(
                old.taskId(),
                old.sessionId(),
                A2aTaskStatus.WAITING_REVIEW,
                old.createdAt(),
                Instant.now(),
                old.requestMessage(),
                old.responsePayload(),
                "HITL_REQUIRED",
                reason == null ? "Human review is required" : reason
        ));
    }

    @Override
    public Optional<A2aTaskSnapshot> markCompleted(String taskId, String responsePayload) {
        return update(taskId, old -> {
            if (old.status() == A2aTaskStatus.CANCELED) {
                return old;
            }
            return new A2aTaskSnapshot(
                    old.taskId(),
                    old.sessionId(),
                    A2aTaskStatus.COMPLETED,
                    old.createdAt(),
                    Instant.now(),
                    old.requestMessage(),
                    responsePayload == null ? "" : responsePayload,
                    "",
                    ""
            );
        });
    }

    @Override
    public Optional<A2aTaskSnapshot> markFailed(String taskId, String errorCode, String errorMessage) {
        return update(taskId, old -> {
            if (old.status() == A2aTaskStatus.CANCELED) {
                return old;
            }
            return new A2aTaskSnapshot(
                    old.taskId(),
                    old.sessionId(),
                    A2aTaskStatus.FAILED,
                    old.createdAt(),
                    Instant.now(),
                    old.requestMessage(),
                    old.responsePayload(),
                    errorCode == null ? SupervisorErrorCode.INTERNAL_ERROR.value() : errorCode,
                    errorMessage == null ? "Supervisor task failed" : errorMessage
            );
        });
    }

    @Override
    public Optional<A2aTaskSnapshot> cancel(String taskId, String reason) {
        return update(taskId, old -> new A2aTaskSnapshot(
                old.taskId(),
                old.sessionId(),
                A2aTaskStatus.CANCELED,
                old.createdAt(),
                Instant.now(),
                old.requestMessage(),
                old.responsePayload(),
                SupervisorErrorCode.CANCELED.value(),
                reason == null || reason.isBlank() ? "Canceled by request" : reason
        ));
    }

    /**
     * 원자적 상태 전이 업데이트.
     * <p>
     * 기존 get->put 패턴의 경쟁 상태를 줄이기 위해 ConcurrentMap.compute를 사용한다.
     * 동시 갱신 시 마지막 write 유실/역전 가능성을 최소화하는 목적이다.
     */
    private Optional<A2aTaskSnapshot> update(String taskId, Function<A2aTaskSnapshot, A2aTaskSnapshot> updater) {
        java.util.concurrent.atomic.AtomicReference<A2aTaskSnapshot> updatedRef = new java.util.concurrent.atomic.AtomicReference<>();
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
