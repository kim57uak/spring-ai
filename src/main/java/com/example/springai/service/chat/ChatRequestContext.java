package com.example.springai.service.chat;

/**
 * 채팅 요청 실행 시 부가 문맥을 담는 값 객체.
 * <p>
 * 포함 정보:
 * - 세션 ID
 * - MCP 도구 콜백 사용 여부
 * - 모델 오버라이드 문자열
 */
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

    /**
     * 세션 식별자 유효성을 확인한다.
     * <p>
     * null/blank가 아니면 true를 반환한다.
     */
    public boolean hasSessionId() {
        return sessionId != null && !sessionId.isBlank();
    }

    /**
     * 요청 모델 오버라이드 값 유효성을 확인한다.
     * <p>
     * null/blank가 아니면 true를 반환한다.
     */
    public boolean hasRequestedModel() {
        return requestedModel != null && !requestedModel.isBlank();
    }
}
