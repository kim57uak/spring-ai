package com.example.springsupervisorai.a2a.lifecycle;

import com.example.springsupervisorai.a2a.task.A2ATaskStore;
import com.example.springsupervisorai.a2a.task.A2aTaskSnapshot;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class SupervisorA2aLifecycleService {

    private final A2ATaskStore taskStore;

    public SupervisorA2aLifecycleService(A2ATaskStore taskStore) {
        this.taskStore = taskStore;
    }

    public A2aTaskSnapshot createAndMarkRunning(String sessionId, String requestMessage) {
        A2aTaskSnapshot created = taskStore.create(sessionId, requestMessage);
        return taskStore.markRunning(created.taskId()).orElse(created);
    }

    public Optional<A2aTaskSnapshot> get(String taskId) {
        return taskStore.get(taskId);
    }

    /**
     * 세션 소유권 기반 단건 조회.
     * <p>
     * 동시 사용자 환경에서 타 세션 task 노출을 막기 위해
     * task의 sessionId와 호출자 sessionId가 일치하는 경우에만 반환한다.
     */
    public Optional<A2aTaskSnapshot> get(String taskId, String sessionId) {
        return taskStore.get(taskId)
                .filter(snapshot -> snapshot.sessionId() != null && snapshot.sessionId().equals(sessionId));
    }

    public List<A2aTaskSnapshot> list(int limit) {
        return taskStore.list(limit);
    }

    /**
     * 세션 소유권 기반 목록 조회.
     * <p>
     * 기존 전역 list 결과를 호출자 sessionId로 필터링해
     * 타 사용자 task가 응답에 포함되지 않도록 보정한다.
     */
    public List<A2aTaskSnapshot> list(String sessionId, int limit) {
        return taskStore.list(limit).stream()
                .filter(snapshot -> snapshot.sessionId() != null && snapshot.sessionId().equals(sessionId))
                .toList();
    }

    public Optional<A2aTaskSnapshot> cancel(String taskId, String reason) {
        return taskStore.cancel(taskId, reason);
    }

    /**
     * 세션 소유권 기반 취소.
     * <p>
     * 먼저 task 소유권을 확인한 뒤 취소를 수행해
     * 인증된 세션 간의 교차 취소를 방지한다.
     */
    public Optional<A2aTaskSnapshot> cancel(String taskId, String sessionId, String reason) {
        Optional<A2aTaskSnapshot> owned = get(taskId, sessionId);
        if (owned.isEmpty()) {
            return Optional.empty();
        }
        return taskStore.cancel(taskId, reason)
                .filter(snapshot -> snapshot.sessionId() != null && snapshot.sessionId().equals(sessionId));
    }

    public void markCompleted(String taskId, String responsePayload) {
        taskStore.markCompleted(taskId, responsePayload);
    }

    public void markFailed(String taskId, String code, String message) {
        taskStore.markFailed(taskId, code, message);
    }
}
