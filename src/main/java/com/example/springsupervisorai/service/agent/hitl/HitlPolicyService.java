package com.example.springsupervisorai.service.agent.hitl;

import com.example.springsupervisorai.model.HitlPolicyResult;

/**
 * HITL 정책 평가 포트.
 */
public interface HitlPolicyService {

    /**
     * 사용자 메시지를 평가해 리뷰 필요 여부를 반환한다.
     *
     * @param sessionId 세션 id
     * @param message 사용자 메시지
     * @param model 모델 식별자
     * @return HITL 정책 평가 결과
     */
    HitlPolicyResult evaluate(String sessionId, String message, String model);
}
