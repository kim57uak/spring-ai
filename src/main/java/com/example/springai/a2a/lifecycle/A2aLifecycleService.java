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

    public List<A2aTaskSnapshot> list(AgentScopeName scopeName, int limit) {
        return taskStore.list(scopeName, limit);
    }

    public Optional<A2aTaskSnapshot> cancel(String taskId, AgentScopeName scopeName, String reason) {
        return taskStore.cancel(taskId, scopeName, reason);
    }

    public void markCompleted(String taskId, AgentScopeName scopeName, String responsePayload) {
        taskStore.markCompleted(taskId, scopeName, responsePayload);
    }

    public void markFailed(String taskId, AgentScopeName scopeName, String code, String message) {
        taskStore.markFailed(taskId, scopeName, code, message);
    }
}
