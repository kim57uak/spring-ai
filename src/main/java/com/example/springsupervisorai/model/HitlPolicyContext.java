package com.example.springsupervisorai.model;

/**
 * Input model for HITL policy evaluation.
 *
 * @param sessionId caller session identifier
 * @param message user message under evaluation
 * @param model requested LLM model
 */
public record HitlPolicyContext(
        String sessionId,
        String message,
        String model
) {

    /**
     * Creates a null-safe context for HITL policy evaluation.
     */
    public static HitlPolicyContext of(String sessionId, String message, String model) {
        return new HitlPolicyContext(
                sessionId == null ? "" : sessionId,
                message == null ? "" : message,
                model == null ? "" : model
        );
    }
}
