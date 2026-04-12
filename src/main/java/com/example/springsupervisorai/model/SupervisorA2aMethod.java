package com.example.springsupervisorai.model;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Supervisor A2A JSON-RPC method를 중앙 관리하는 enum.
 * <p>
 * 목적:
 * - 신규(v1.0 PascalCase)와 기존(legacy slash-case) 메서드를 동시에 수용한다.
 * - 컨트롤러/플래너/인보커가 동일한 정규화 기준으로 분기하도록 한다.
 */
public enum SupervisorA2aMethod {
    SEND_MESSAGE("SendMessage"),
    SEND_STREAMING_MESSAGE("SendStreamingMessage"),
    GET_TASK("GetTask"),
    LIST_TASKS("ListTasks"),
    CANCEL_TASK("CancelTask"),
    MESSAGE_SEND("message/send"),
    MESSAGE_STREAM("message/stream"),
    TASKS_GET("tasks/get"),
    TASKS_LIST("tasks/list"),
    TASKS_CANCEL("tasks/cancel");

    private final String value;

    SupervisorA2aMethod(String value) {
        this.value = value;
    }

    /**
     * method 문자열 값을 반환한다.
     *
     * @return JSON-RPC method 문자열
     */
    public String value() {
        return value;
    }

    /**
     * method 문자열을 enum으로 역변환한다.
     *
     * @param method JSON-RPC method 문자열
     * @return method enum(optional)
     */
    public static Optional<SupervisorA2aMethod> from(String method) {
        return Arrays.stream(values())
                .filter(candidate -> candidate.value.equals(method))
                .findFirst();
    }

    /**
     * send 계열 메서드인지 판별한다.
     * <p>
     * v1.0 `SendMessage`와 legacy `message/send`를 동일 그룹으로 취급한다.
     *
     * @return send 계열이면 true
     */
    public boolean isSend() {
        return this == SEND_MESSAGE || this == MESSAGE_SEND;
    }

    /**
     * stream 계열 메서드인지 판별한다.
     * <p>
     * v1.0 `SendStreamingMessage`와 legacy `message/stream`를 동일 그룹으로 취급한다.
     *
     * @return stream 계열이면 true
     */
    public boolean isStream() {
        return this == SEND_STREAMING_MESSAGE || this == MESSAGE_STREAM;
    }

    /**
     * task-get 계열 메서드인지 판별한다.
     *
     * @return task 조회 계열이면 true
     */
    public boolean isTaskGet() {
        return this == GET_TASK || this == TASKS_GET;
    }

    /**
     * task-list 계열 메서드인지 판별한다.
     *
     * @return task 목록 계열이면 true
     */
    public boolean isTaskList() {
        return this == LIST_TASKS || this == TASKS_LIST;
    }

    /**
     * task-cancel 계열 메서드인지 판별한다.
     *
     * @return task 취소 계열이면 true
     */
    public boolean isTaskCancel() {
        return this == CANCEL_TASK || this == TASKS_CANCEL;
    }

    /**
     * outbound 호출 시 우선 사용할 send 기본 메서드를 반환한다.
     *
     * @return 기본 send 메서드(`SendMessage`)
     */
    public static String preferredSendMethod() {
        return SEND_MESSAGE.value();
    }

    /**
     * outbound 호출 시 우선 사용할 stream 기본 메서드를 반환한다.
     *
     * @return 기본 stream 메서드(`SendStreamingMessage`)
     */
    public static String preferredStreamMethod() {
        return SEND_STREAMING_MESSAGE.value();
    }

    /**
     * 모든 method 문자열 집합을 반환한다.
     *
     * @return allowlist 용 method 문자열 집합
     */
    public static Set<String> valuesSet() {
        return Arrays.stream(values())
                .map(SupervisorA2aMethod::value)
                .collect(Collectors.toUnmodifiableSet());
    }
}
