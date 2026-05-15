package com.example.springsupervisorai.model;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * HITL 리뷰 상태 전이를 관리하는 불변 상태 머신.
 * <p>
 * {@link HitlReviewStatus} 간 유효 전이 규칙을 중앙에 정의하고,
 * 실행 시점에 유효성을 검증한다. WAITING 상태에서만 APPROVED / CANCELED / REVISED
 * 전이가 허용되며, 터미널 상태에서는 자기 자신으로만 전이 가능하다.
 */
public final class HitlStateMachine {

    private static final Map<HitlReviewStatus, Set<HitlReviewStatus>> TRANSITIONS = new EnumMap<>(HitlReviewStatus.class);

    static {
        TRANSITIONS.put(HitlReviewStatus.WAITING, Set.of(
                HitlReviewStatus.APPROVED,
                HitlReviewStatus.CANCELED,
                HitlReviewStatus.REVISED
        ));
        TRANSITIONS.put(HitlReviewStatus.APPROVED, Set.of(HitlReviewStatus.APPROVED));
        TRANSITIONS.put(HitlReviewStatus.CANCELED, Set.of(HitlReviewStatus.CANCELED));
        TRANSITIONS.put(HitlReviewStatus.REVISED, Set.of(HitlReviewStatus.REVISED));
    }

    private HitlStateMachine() {
    }

    public static HitlReviewStatus transition(HitlReviewStatus current, HitlDecisionType decision) {
        if (current == null) {
            throw new IllegalArgumentException("current status must not be null");
        }
        HitlReviewStatus target = toTargetStatus(decision);
        Set<HitlReviewStatus> allowed = TRANSITIONS.get(current);
        if (allowed == null || !allowed.contains(target)) {
            throw new IllegalStateException(
                    "Invalid transition: " + current + " → " + target + " (via " + decision + ")");
        }
        return target;
    }

    public static boolean isTerminal(HitlReviewStatus status) {
        return status != null && status != HitlReviewStatus.WAITING;
    }

    private static HitlReviewStatus toTargetStatus(HitlDecisionType decision) {
        return switch (decision) {
            case APPROVE -> HitlReviewStatus.APPROVED;
            case CANCEL -> HitlReviewStatus.CANCELED;
            case REVISE -> HitlReviewStatus.REVISED;
        };
    }
}
