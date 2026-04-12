package com.example.springsupervisorai.model;

/**
 * Supervisor 실행 상태 문자열을 중앙 관리하는 enum.
 */
public enum SupervisorRuntimeState {
    REQUEST_VALIDATED("REQUEST_VALIDATED"),
    HISTORY_LOADED("HISTORY_LOADED"),
    PLANNED("PLANNED"),
    ROUTING_SELECTED("ROUTING_SELECTED"),
    A2A_CALLING("A2A_CALLING"),
    A2A_RESULT_MERGED("A2A_RESULT_MERGED"),
    COMPOSING("COMPOSING"),
    COMPLETED("COMPLETED");

    private final String value;

    SupervisorRuntimeState(String value) {
        this.value = value;
    }

    /**
     * 상태 문자열 값을 반환한다.
     *
     * @return runtime state value
     */
    public String value() {
        return value;
    }
}

