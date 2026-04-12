package com.example.springsupervisorai.model;

import java.util.Arrays;
import java.util.Optional;

/**
 * HITL 검토 결정 타입.
 * <p>
 * 현재 스코프는 승인/취소만 허용한다.
 */
public enum HitlDecisionType {
    APPROVE,
    CANCEL;

    /**
     * 문자열 입력을 결정 타입으로 변환한다.
     *
     * @param raw 사용자/요청 입력 문자열
     * @return 변환된 결정 타입(optional)
     */
    public static Optional<HitlDecisionType> from(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(value -> value.name().equalsIgnoreCase(raw.trim()))
                .findFirst();
    }
}
