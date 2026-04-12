package com.example.springsupervisorai.a2a.task;

import java.time.Instant;

public record A2aTaskSnapshot(
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
}

