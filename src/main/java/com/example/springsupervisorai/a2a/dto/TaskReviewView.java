package com.example.springsupervisorai.a2a.dto;

/**
 * HITL review 조회 응답 뷰.
 *
 * @param id task id
 * @param status review 상태
 * @param policyId 정책 id
 * @param policyReason 정책 사유
 * @param decisionReason 결정 사유
 * @param requestedAt 리뷰 요청 시각
 * @param decidedAt 리뷰 결정 시각
 * @param expiresAt 리뷰 만료 시각
 */
public record TaskReviewView(
        String id,
        String status,
        String policyId,
        String policyReason,
        String decisionReason,
        String requestedAt,
        String decidedAt,
        String expiresAt
) {
}
