package com.example.springai.model.agent;

import com.example.springai.a2a.context.A2aExecutionContext;
import jakarta.validation.constraints.NotBlank;

public record AgentChatRequest(
        @NotBlank String sessionId,
        @NotBlank String message,
        String model,
        AgentScope scope,
        A2aExecutionContext a2aContext
) {
    public AgentChatRequest(String sessionId, String message, String model) {
        this(sessionId, message, model, AgentScope.unrestricted(), null);
    }

    public AgentChatRequest(String sessionId, String message, String model, AgentScope scope) {
        this(sessionId, message, model, scope, null);
    }
}
