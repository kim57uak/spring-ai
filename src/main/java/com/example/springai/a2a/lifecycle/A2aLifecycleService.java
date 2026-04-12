package com.example.springai.a2a.lifecycle;

import com.example.springai.a2a.task.A2ATaskStore;
import com.example.springai.a2a.task.A2aTaskSnapshot;
import com.example.springai.model.agent.AgentScopeName;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * A2A 작업 상태 전이를 담당하는 애플리케이션 서비스.
 * 생성/조회/목록/취소 및 완료·실패 상태 업데이트를 캡슐화한다.
 */
@Service
public class A2aLifecycleService {

    private final A2ATaskStore taskStore;

    public A2aLifecycleService(A2ATaskStore taskStore) {
        this.taskStore = taskStore;
    }

    public A2aTaskSnapshot createAndMarkRunning(AgentScopeName scopeName, String sessionId, String requestMessage) {
        A2aTaskSnapshot created = taskStore.create(scopeName, sessionId, requestMessage);
        return taskStore.markRunning(created.taskId(), scopeName).orElse(created);
    }

    public Optional<A2aTaskSnapshot> get(String taskId, AgentScopeName scopeName) {
        return taskStore.get(taskId, scopeName);
    }

    /**
     * scope + 세션 소유권 기반 단건 조회.
     * <p>
     * 세션ID가 인증되었다는 전제 하에 task 소유자 검증을 수행해
     * 동시 사용자 간 교차 조회를 차단한다.
     */
    public Optional<A2aTaskSnapshot> get(String taskId, AgentScopeName scopeName, String sessionId) {
        return taskStore.get(taskId, scopeName)
                .filter(snapshot -> snapshot.sessionId() != null && snapshot.sessionId().equals(sessionId));
    }

    public List<A2aTaskSnapshot> list(AgentScopeName scopeName, int limit) {
        return taskStore.list(scopeName, limit);
    }

    /**
     * scope + 세션 소유권 기반 목록 조회.
     * <p>
     * 기존 scope 목록에서 호출자 sessionId로 재필터링해
     * 타 세션 task가 목록에 포함되지 않도록 보장한다.
     */
    public List<A2aTaskSnapshot> list(AgentScopeName scopeName, String sessionId, int limit) {
        return taskStore.list(scopeName, limit).stream()
                .filter(snapshot -> snapshot.sessionId() != null && snapshot.sessionId().equals(sessionId))
                .toList();
    }

    public Optional<A2aTaskSnapshot> cancel(String taskId, AgentScopeName scopeName, String reason) {
        return taskStore.cancel(taskId, scopeName, reason);
    }

    /**
     * scope + 세션 소유권 기반 취소.
     * <p>
     * 취소 전에 소유권을 확인해 동시접속 환경에서
     * 타 사용자 task 상태를 변경하지 못하게 한다.
     */
    public Optional<A2aTaskSnapshot> cancel(String taskId, AgentScopeName scopeName, String sessionId, String reason) {
        Optional<A2aTaskSnapshot> owned = get(taskId, scopeName, sessionId);
        if (owned.isEmpty()) {
            return Optional.empty();
        }
        return taskStore.cancel(taskId, scopeName, reason)
                .filter(snapshot -> snapshot.sessionId() != null && snapshot.sessionId().equals(sessionId));
    }

    public void markCompleted(String taskId, AgentScopeName scopeName, String responsePayload) {
        taskStore.markCompleted(taskId, scopeName, responsePayload);
    }

    public void markFailed(String taskId, AgentScopeName scopeName, String code, String message) {
        taskStore.markFailed(taskId, scopeName, code, message);
    }
}
