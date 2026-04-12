package com.example.springsupervisorai.model;

/**
 * HITL 정책 평가 결과.
 *
 * @param required 리뷰 필요 여부
 * @param policyId 적용 정책 식별자
 * @param reason 정책 판단 사유
 */
public record HitlPolicyResult(
        boolean required,
        String policyId,
        String reason
) {
    /**
     * 리뷰가 필요 없는 기본 결과를 반환한다.
     *
     * @return not-required 결과
     */
    public static HitlPolicyResult notRequired() {
        return new HitlPolicyResult(false, "", "");
    }
}
