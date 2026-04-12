package com.example.springai.a2a.task;

import com.example.springai.model.agent.AgentScopeName;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 로컬 실행 및 테스트용 {@link A2ATaskStore} 인메모리 구현체.
 */
@Component
public class InMemoryA2ATaskStore implements A2ATaskStore {

    private final ConcurrentMap<String, A2aTaskSnapshot> tasks = new ConcurrentHashMap<>();

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

    @Override
    public Optional<A2aTaskSnapshot> get(String taskId, AgentScopeName scopeName) {
        A2aTaskSnapshot snapshot = tasks.get(taskId);
        if (snapshot == null || snapshot.scopeName() != scopeName) {
            return Optional.empty();
        }
        return Optional.of(snapshot);
    }

    @Override
    public List<A2aTaskSnapshot> list(AgentScopeName scopeName, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        return tasks.values().stream()
                .filter(task -> task.scopeName() == scopeName)
                .sorted(Comparator.comparing(A2aTaskSnapshot::updatedAt).reversed())
                .limit(safeLimit)
                .toList();
    }

    @Override
    public Optional<A2aTaskSnapshot> markRunning(String taskId, AgentScopeName scopeName) {
        return update(taskId, scopeName, old -> new A2aTaskSnapshot(
                old.taskId(),
                old.scopeName(),
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

    @Override
    public Optional<A2aTaskSnapshot> markCompleted(String taskId, AgentScopeName scopeName, String responsePayload) {
        return update(taskId, scopeName, old -> new A2aTaskSnapshot(
                old.taskId(),
                old.scopeName(),
                old.sessionId(),
                old.status() == A2aTaskStatus.CANCELED ? A2aTaskStatus.CANCELED : A2aTaskStatus.COMPLETED,
                old.createdAt(),
                Instant.now(),
                old.requestMessage(),
                old.status() == A2aTaskStatus.CANCELED ? old.responsePayload() : (responsePayload == null ? "" : responsePayload),
                old.status() == A2aTaskStatus.CANCELED ? old.errorCode() : "",
                old.status() == A2aTaskStatus.CANCELED ? old.errorMessage() : ""
        ));
    }

    @Override
    public Optional<A2aTaskSnapshot> markFailed(
            String taskId,
            AgentScopeName scopeName,
            String errorCode,
            String errorMessage
    ) {
        return update(taskId, scopeName, old -> new A2aTaskSnapshot(
                old.taskId(),
                old.scopeName(),
                old.sessionId(),
                old.status() == A2aTaskStatus.CANCELED ? A2aTaskStatus.CANCELED : A2aTaskStatus.FAILED,
                old.createdAt(),
                Instant.now(),
                old.requestMessage(),
                old.responsePayload(),
                old.status() == A2aTaskStatus.CANCELED ? old.errorCode() : (errorCode == null ? "INTERNAL_ERROR" : errorCode),
                old.status() == A2aTaskStatus.CANCELED ? old.errorMessage() : (errorMessage == null ? "A2A task failed" : errorMessage)
        ));
    }

    @Override
    public Optional<A2aTaskSnapshot> cancel(String taskId, AgentScopeName scopeName, String reason) {
        return update(taskId, scopeName, old -> new A2aTaskSnapshot(
                old.taskId(),
                old.scopeName(),
                old.sessionId(),
                A2aTaskStatus.CANCELED,
                old.createdAt(),
                Instant.now(),
                old.requestMessage(),
                old.responsePayload(),
                "CANCELED",
                reason == null || reason.isBlank() ? "Canceled by request" : reason
        ));
    }

    /**
     * 원자적 상태 전이 업데이트(scope 한정).
     * <p>
     * get->put 방식의 레이스를 완화하기 위해 compute를 사용하고,
     * scope 불일치 항목은 그대로 유지해 잘못된 상태 변이를 방지한다.
     */
    private Optional<A2aTaskSnapshot> update(
            String taskId,
            AgentScopeName scopeName,
            java.util.function.Function<A2aTaskSnapshot, A2aTaskSnapshot> updater
    ) {
        java.util.concurrent.atomic.AtomicReference<A2aTaskSnapshot> updatedRef = new java.util.concurrent.atomic.AtomicReference<>();
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
