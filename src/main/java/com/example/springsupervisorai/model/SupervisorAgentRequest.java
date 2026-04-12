package com.example.springsupervisorai.model;

public record SupervisorAgentRequest(
        String sessionId,
        String message,
        String model
) {
}

