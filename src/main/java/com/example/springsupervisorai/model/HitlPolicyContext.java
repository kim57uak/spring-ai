package com.example.springsupervisorai.model;

/**
 * HITL 정책 평가 입력 모델.
 *
 * @param sessionId 호출자 세션 식별자
 * @param message 평가 대상 사용자 메시지
 * @param model 요청된 LLM 모델
 */
public record HitlPolicyContext(
        String sessionId,
        String message,
        String model
) {

    /**
     * HITL 정책 평가를 위한 널-세이프 컨텍스트를 생성한다.
     */
    public static HitlPolicyContext of(String sessionId, String message, String model) {
        return new HitlPolicyContext(
                sessionId == null ? "" : sessionId,
                message == null ? "" : message,
                model == null ? "" : model
        );
    }
}
