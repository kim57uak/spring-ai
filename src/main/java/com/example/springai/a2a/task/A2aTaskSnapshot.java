package com.example.springai.a2a.task;

import com.example.springai.model.agent.AgentScopeName;

import java.time.Instant;

/**
 * 특정 시점의 A2A 작업 상태를 담는 불변 스냅샷.
 */
public record A2aTaskSnapshot(
        String taskId,
        AgentScopeName scopeName,
        String sessionId,
        A2aTaskStatus status,
        Instant createdAt,
        Instant updatedAt,
        String requestMessage,
        String responsePayload,
        String errorCode,
        String errorMessage,
        String contextId
) {
    public A2aTaskSnapshot(
            String taskId,
            AgentScopeName scopeName,
            String sessionId,
            A2aTaskStatus status,
            Instant createdAt,
            Instant updatedAt,
            String requestMessage,
            String responsePayload,
            String errorCode,
            String errorMessage
    ) {
        this(taskId, scopeName, sessionId, status, createdAt, updatedAt, requestMessage, responsePayload, errorCode, errorMessage, null);
    }
}
