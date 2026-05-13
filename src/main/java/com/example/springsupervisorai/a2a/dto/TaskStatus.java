package com.example.springsupervisorai.a2a.dto;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;
import java.util.Optional;

public enum TaskStatus {
    SUBMITTED("submitted"),
    WORKING("working"),
    INPUT_REQUIRED("input-required"),
    COMPLETED("completed"),
    CANCELED("canceled"),
    FAILED("failed"),
    REJECTED("rejected"),
    AUTH_REQUIRED("auth-required"),
    UNKNOWN("unknown");

    private final String value;

    TaskStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    public static Optional<TaskStatus> from(String value) {
        if (value == null) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(s -> s.value.equals(value))
                .findFirst();
    }
}
