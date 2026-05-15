package com.example.springsupervisorai.model;

/**
 * supervisor 에이전트 호출 요청.
 */
public record SupervisorAgentRequest(
        String sessionId,
        String message,
        String model
) {
}

