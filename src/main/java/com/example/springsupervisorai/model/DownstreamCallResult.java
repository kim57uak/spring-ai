package com.example.springsupervisorai.model;

public record DownstreamCallResult(
        String agentKey,
        String taskId,
        String status,
        String payload,
        String errorCode,
        String errorMessage
) {
}

