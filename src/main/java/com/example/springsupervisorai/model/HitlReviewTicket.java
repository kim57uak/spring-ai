package com.example.springsupervisorai.model;

import java.time.Instant;

/**
 * HITL 리뷰 티켓 상태 스냅샷.
 *
 * @param taskId supervisor task 식별자
 * @param sessionId 요청 세션 식별자
 * @param message 원본 사용자 메시지
 * @param model 실행 모델
 * @param policyId 적용 정책 식별자
 * @param policyReason 정책 적용 사유
 * @param status 현재 리뷰 상태
 * @param decisionReason 승인/취소 사유
 * @param requestedAt 리뷰 요청 시각
 * @param expiresAt 리뷰 만료 시각
 * @param decidedAt 리뷰 결정 시각
 * @param decisionId 결정 idempotency 식별자
 */
public record HitlReviewTicket(
        String taskId,
        String sessionId,
        String message,
        String model,
        String policyId,
        String policyReason,
        HitlReviewStatus status,
        String decisionReason,
        Instant requestedAt,
        Instant expiresAt,
        Instant decidedAt,
        String decisionId,
        String revisedMessage
) {
    // Compact constructor for validation
    public HitlReviewTicket {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId must not be blank");
        }
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
    }

    // 기존 생성자 호환성을 위한 팩토리 메서드
    public static HitlReviewTicket create(
            String taskId,
            String sessionId,
            String message,
            String model,
            String policyId,
            String policyReason,
            HitlReviewStatus status,
            String decisionReason,
            Instant requestedAt,
            Instant expiresAt,
            Instant decidedAt,
            String decisionId
    ) {
        return new HitlReviewTicket(
                taskId, sessionId, message, model, policyId, policyReason, status,
                decisionReason, requestedAt, expiresAt, decidedAt, decisionId, null);
    }

    public static HitlReviewTicket create(
            String taskId,
            String sessionId,
            String message,
            String model,
            String policyId,
            String policyReason,
            HitlReviewStatus status,
            String decisionReason,
            Instant requestedAt,
            Instant expiresAt,
            Instant decidedAt,
            String decisionId,
            String revisedMessage
    ) {
        return new HitlReviewTicket(
                taskId, sessionId, message, model, policyId, policyReason, status,
                decisionReason, requestedAt, expiresAt, decidedAt, decisionId, revisedMessage);
    }

    /**
     * 리뷰가 대기 상태인지 반환한다.
     *
     * @return WAITING 상태이면 true
     */
    public boolean isWaiting() {
        return status == HitlReviewStatus.WAITING;
    }

    /**
     * 상태를 변경한 새로운 티켓을 반환한다.
     */
    public HitlReviewTicket withStatus(HitlReviewStatus newStatus) {
        return new HitlReviewTicket(
                taskId, sessionId, message, model, policyId, policyReason, newStatus,
                decisionReason, requestedAt, expiresAt, Instant.now(), decisionId, revisedMessage);
    }

    /**
     * 수정된 메시지를 반영한 새로운 티켓을 반환한다.
     */
    public HitlReviewTicket withRevisedMessage(String revisedMessage) {
        return new HitlReviewTicket(
                taskId, sessionId, message, model, policyId, policyReason, status,
                decisionReason, requestedAt, expiresAt, decidedAt, decisionId, revisedMessage);
    }
}
