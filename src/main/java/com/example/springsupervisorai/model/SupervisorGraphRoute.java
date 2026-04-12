package com.example.springsupervisorai.model;

/**
 * LangGraph 조건 분기에서 사용하는 라우트 키를 중앙 관리한다.
 * <p>
 * 문자열 리터럴을 분산해서 사용하면 오타/리팩터링 누락 위험이 높아지므로
 * enum 값으로 고정해 그래프 분기 계약을 명시적으로 유지한다.
 */
public enum SupervisorGraphRoute {
    INVOKE("invoke"),
    COMPOSE("compose");

    private final String value;

    SupervisorGraphRoute(String value) {
        this.value = value;
    }

    /**
     * LangGraph conditional edge key 문자열을 반환한다.
     *
     * @return 그래프 분기 라우트 키
     */
    public String value() {
        return value;
    }
}
