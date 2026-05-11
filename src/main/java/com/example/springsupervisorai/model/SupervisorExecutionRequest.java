package com.example.springsupervisorai.model;

/**
 * Supervisor 실행 요청의 공통 입력 모델.
 *
 * @param sessionId 세션 id
 * @param message 사용자 메시지
 * @param model 모델 식별자
 */
public record SupervisorExecutionRequest(
        String sessionId,
        String message,
        String model
) {

    /**
     * 오케스트레이터 입력 모델로 변환한다.
     *
     * @return supervisor agent request
     */
    public SupervisorAgentRequest toAgentRequest() {
        return new SupervisorAgentRequest(sessionId, message, model != null ? model : "claude-3");
    }

    /**
     * model이 null일 경우 기본값을 사용하는 팩토리 메서드.
     *
     * @param sessionId 세션 id
     * @param message 사용자 메시지
     * @return SupervisorExecutionRequest
     */
    public static SupervisorExecutionRequest of(String sessionId, String message) {
        return new SupervisorExecutionRequest(sessionId, message, "claude-3");
    }
}
