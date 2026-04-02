package com.example.springai.service.chat;

public record ChatRequestContext(String sessionId, boolean mcpToolCallbacksEnabled, String requestedModel) {

    private static final ChatRequestContext EMPTY = new ChatRequestContext("", false, "");

    public static ChatRequestContext empty() {
        return EMPTY;
    }

    public static ChatRequestContext of(String sessionId, boolean mcpToolCallbacksEnabled) {
        return new ChatRequestContext(sessionId, mcpToolCallbacksEnabled, "");
    }

    public static ChatRequestContext of(String sessionId, boolean mcpToolCallbacksEnabled, String requestedModel) {
        return new ChatRequestContext(sessionId, mcpToolCallbacksEnabled, requestedModel);
    }

    public boolean hasSessionId() {
        return sessionId != null && !sessionId.isBlank();
    }

    public boolean hasRequestedModel() {
        return requestedModel != null && !requestedModel.isBlank();
    }
}
