package com.example.springai.a2a;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 하위 에이전트 A2A 메서드 문자열을 중앙 관리한다.
 * <p>
 * 목적:
 * - 신규(v1.0 PascalCase)와 기존(legacy slash-case) 메서드명을 동시에 수용한다.
 * - 컨트롤러에서 문자열 하드코딩을 제거해 호환 정책 변경 시 수정 지점을 단일화한다.
 */
public enum A2aMethod {
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

    A2aMethod(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static Optional<A2aMethod> from(String method) {
        return Arrays.stream(values())
                .filter(candidate -> candidate.value.equals(method))
                .findFirst();
    }

    public boolean isSend() {
        return this == SEND_MESSAGE || this == MESSAGE_SEND;
    }

    public boolean isStream() {
        return this == SEND_STREAMING_MESSAGE || this == MESSAGE_STREAM;
    }

    public boolean isTaskGet() {
        return this == GET_TASK || this == TASKS_GET;
    }

    public boolean isTaskList() {
        return this == LIST_TASKS || this == TASKS_LIST;
    }

    public boolean isTaskCancel() {
        return this == CANCEL_TASK || this == TASKS_CANCEL;
    }

    /**
     * idempotency 키 생성 시 send 계열을 같은 의미로 묶기 위한 표준 키.
     *
     * @return dedupe 표준 문자열
     */
    public static String sendDedupeKey() {
        return "send-message";
    }

    public static Set<String> valuesSet() {
        return Arrays.stream(values())
                .map(A2aMethod::value)
                .collect(Collectors.toUnmodifiableSet());
    }
}
