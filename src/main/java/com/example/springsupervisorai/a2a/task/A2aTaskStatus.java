package com.example.springsupervisorai.a2a.task;

/**
 * 내부 A2A task 상태 값. A2A protocol 상태에 WAITING_REVIEW가 추가되었다.
 */
public enum A2aTaskStatus {
    SUBMITTED,
    WORKING,
    WAITING_REVIEW,
    INPUT_REQUIRED,
    COMPLETED,
    CANCELED,
    FAILED,
    REJECTED,
    AUTH_REQUIRED,
    UNKNOWN
}
