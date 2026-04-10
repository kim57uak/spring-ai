package com.example.springai.model.agent;

import jakarta.validation.constraints.NotBlank;

public record AgentChatRequest(
        @NotBlank String sessionId,
        @NotBlank String message,
        String model,
        AgentScope scope
) {
    public AgentChatRequest(String sessionId, String message, String model) {
        this(sessionId, message, model, AgentScope.unrestricted());
    }
}
