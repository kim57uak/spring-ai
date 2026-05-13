package com.example.springsupervisorai.model;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

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
