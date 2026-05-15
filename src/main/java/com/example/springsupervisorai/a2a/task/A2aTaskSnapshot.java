package com.example.springsupervisorai.a2a.task;

import java.time.Instant;

/**
 * A2A task 상태 스냅샷. 영속화 및 조회용 내부 모델.
 */
public record A2aTaskSnapshot(
        String taskId,
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
            String sessionId,
            A2aTaskStatus status,
            Instant createdAt,
            Instant updatedAt,
            String requestMessage,
            String responsePayload,
            String errorCode,
            String errorMessage
    ) {
        this(taskId, sessionId, status, createdAt, updatedAt, requestMessage, responsePayload, errorCode, errorMessage, null);
    }
}

