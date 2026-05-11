package com.example.springsupervisorai.model;

/**
 * HITL 리뷰 상태.
 */
public enum HitlReviewStatus {
    WAITING,    // 리뷰 대기 중
    APPROVED,   // 승인됨
    CANCELED,   // 취소됨
    REVISED     // 수정됨 (신규 추가)
}
