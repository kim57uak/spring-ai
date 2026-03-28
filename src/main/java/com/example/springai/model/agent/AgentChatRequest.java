package com.example.springai.model.agent;

import jakarta.validation.constraints.NotBlank;

public record AgentChatRequest(
        @NotBlank String sessionId,
        @NotBlank String message,
        String model
) {
}
