package com.example.springsupervisorai.model;

/**
 * handoff 정책 검증 결과 모델.
 *
 * @param accepted 정책 검증 통과 여부
 * @param reasonCode 차단/허용 사유 코드
 * @param directive 정규화된 handoff 지시
 * @param plan 검증 통과 시 생성된 라우팅 계획
 * @param hopCount 적용 시점 hop 수
 */
public record HandoffValidationResult(
        boolean accepted,
        String reasonCode,
        HandoffDirective directive,
        RoutingPlan plan,
        int hopCount
) {

    /**
     * 허용 결과를 생성한다.
     */
    public static HandoffValidationResult accepted(
            HandoffDirective directive,
            RoutingPlan plan,
            int hopCount
    ) {
        return new HandoffValidationResult(true, "ACCEPTED", directive, plan, hopCount);
    }

    /**
     * 차단 결과를 생성한다.
     */
    public static HandoffValidationResult rejected(
            String reasonCode,
            HandoffDirective directive,
            int hopCount
    ) {
        return new HandoffValidationResult(false, safe(reasonCode), directive, null, hopCount);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
