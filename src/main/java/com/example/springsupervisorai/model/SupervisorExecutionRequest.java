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
        return new SupervisorAgentRequest(sessionId, message, model);
    }
}
