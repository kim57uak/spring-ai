package com.example.springsupervisorai.service.agent.hitl;

import com.example.springsupervisorai.model.HitlPolicyContext;
import com.example.springsupervisorai.model.HitlPolicyResult;

/**
 * 사용자 메시지에 HITL 리뷰가 필요한지 결정하는 HITL 정책 평가 포트.
 * <p>
 * 평가는 메시지 내용 민감도, 신뢰도 임계값, 프롬프트 템플릿을 통해 구성된
 * 도메인별 규칙과 같은 요소를 고려한다.
 */
public interface HitlPolicyService {

    /**
     * 사용자 메시지를 평가해 리뷰 필요 여부를 반환한다.
     *
     * @param context HITL 정책 입력 컨텍스트
     * @return HITL 정책 평가 결과
     */
    HitlPolicyResult evaluate(HitlPolicyContext context);

    /**
     * primitive 인자 호출부 호환을 위한 bridge 메서드.
     *
     * @param sessionId 세션 id
     * @param message 사용자 메시지
     * @param model 모델 식별자
     * @return HITL 정책 평가 결과
     */
    default HitlPolicyResult evaluate(String sessionId, String message, String model) {
        return evaluate(HitlPolicyContext.of(sessionId, message, model));
    }
}
