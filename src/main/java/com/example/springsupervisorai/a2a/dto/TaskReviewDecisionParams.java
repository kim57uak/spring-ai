package com.example.springsupervisorai.a2a.dto;

/**
 * tasks/review/decide 요청 파라미터.
 *
 * @param id review 대상 task id
 * @param decision 리뷰 결정(APPROVE/CANCEL)
 * @param reason 결정 사유
 * @param decisionId 결정 idempotency id
 */
public record TaskReviewDecisionParams(
        String id,
        String decision,
        String reason,
        String decisionId,
        String revisedMessage
) {
}
