package com.example.springai.a2a.task;

/**
 * A2A 작업 라이프사이클의 표준 상태 집합.
 */
public enum A2aTaskStatus {
    SUBMITTED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELED
}
