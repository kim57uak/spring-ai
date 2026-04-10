package com.example.springai.a2a.task;

import com.example.springai.model.agent.AgentScopeName;

import java.util.List;
import java.util.Optional;

/**
 * A2A 작업 상태와 전이 정보를 저장/조회하는 영속성 경계 인터페이스.
 */
public interface A2ATaskStore {

    A2aTaskSnapshot create(AgentScopeName scopeName, String sessionId, String requestMessage);

    Optional<A2aTaskSnapshot> get(String taskId, AgentScopeName scopeName);

    List<A2aTaskSnapshot> list(AgentScopeName scopeName, int limit);

    Optional<A2aTaskSnapshot> markRunning(String taskId, AgentScopeName scopeName);

    Optional<A2aTaskSnapshot> markCompleted(String taskId, AgentScopeName scopeName, String responsePayload);

    Optional<A2aTaskSnapshot> markFailed(
            String taskId,
            AgentScopeName scopeName,
            String errorCode,
            String errorMessage
    );

    Optional<A2aTaskSnapshot> cancel(String taskId, AgentScopeName scopeName, String reason);
}
