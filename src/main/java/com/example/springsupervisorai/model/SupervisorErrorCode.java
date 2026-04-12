package com.example.springsupervisorai.model;

/**
 * Supervisor 내부 표준 에러 코드를 중앙 관리하는 enum.
 */
public enum SupervisorErrorCode {
    COMPOSE_ERROR("COMPOSE_ERROR"),
    ORCHESTRATION_ERROR("ORCHESTRATION_ERROR"),
    DOWNSTREAM_UNAVAILABLE("DOWNSTREAM_UNAVAILABLE"),
    CIRCUIT_OPEN("CIRCUIT_OPEN"),
    EMPTY_RESPONSE("EMPTY_RESPONSE"),
    DOWNSTREAM_ERROR("DOWNSTREAM_ERROR"),
    INTERNAL_ERROR("INTERNAL_ERROR"),
    CANCELED("CANCELED");

    private final String value;

    SupervisorErrorCode(String value) {
        this.value = value;
    }

    /**
     * 에러 코드 문자열 값을 반환한다.
     *
     * @return 에러 코드 문자열
     */
    public String value() {
        return value;
    }
}
