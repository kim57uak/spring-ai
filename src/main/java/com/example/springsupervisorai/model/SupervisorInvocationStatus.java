package com.example.springsupervisorai.model;

/**
 * downstream 호출 결과 상태 문자열을 중앙 관리하는 enum.
 */
public enum SupervisorInvocationStatus {
    COMPLETED("COMPLETED"),
    FAILED("FAILED");

    private final String value;

    SupervisorInvocationStatus(String value) {
        this.value = value;
    }

    /**
     * 상태 문자열 값을 반환한다.
     *
     * @return invocation status 문자열
     */
    public String value() {
        return value;
    }
}
