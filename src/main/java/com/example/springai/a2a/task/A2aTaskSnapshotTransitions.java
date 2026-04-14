package com.example.springai.a2a.task;

import java.time.Instant;

/**
 * SpringAI A2A task 스냅샷 상태 전이 유틸리티.
 * <p>
 * 상태 전이 규칙을 저장소 구현에서 분리해 가독성과 테스트 용이성을 높인다.
 */
final class A2aTaskSnapshotTransitions {

    private A2aTaskSnapshotTransitions() {
    }

    /**
     * RUNNING 상태로 전이한다.
     */
    static A2aTaskSnapshot markRunning(A2aTaskSnapshot current, Instant now) {
        return new A2aTaskSnapshot(
                current.taskId(),
                current.scopeName(),
                current.sessionId(),
                A2aTaskStatus.RUNNING,
                current.createdAt(),
                now,
                current.requestMessage(),
                current.responsePayload(),
                current.errorCode(),
                current.errorMessage()
        );
    }

    /**
     * COMPLETED 상태로 전이한다.
     * <p>
     * 이미 CANCELED 상태인 task는 상태와 응답/에러 정보를 유지한다.
     */
    static A2aTaskSnapshot markCompleted(A2aTaskSnapshot current, String responsePayload, Instant now) {
        return new A2aTaskSnapshot(
                current.taskId(),
                current.scopeName(),
                current.sessionId(),
                current.status() == A2aTaskStatus.CANCELED ? A2aTaskStatus.CANCELED : A2aTaskStatus.COMPLETED,
                current.createdAt(),
                now,
                current.requestMessage(),
                current.status() == A2aTaskStatus.CANCELED ? current.responsePayload() : (responsePayload == null ? "" : responsePayload),
                current.status() == A2aTaskStatus.CANCELED ? current.errorCode() : "",
                current.status() == A2aTaskStatus.CANCELED ? current.errorMessage() : ""
        );
    }

    /**
     * FAILED 상태로 전이한다.
     * <p>
     * 이미 CANCELED 상태인 task는 상태와 에러 정보를 유지한다.
     */
    static A2aTaskSnapshot markFailed(A2aTaskSnapshot current, String errorCode, String errorMessage, Instant now) {
        return new A2aTaskSnapshot(
                current.taskId(),
                current.scopeName(),
                current.sessionId(),
                current.status() == A2aTaskStatus.CANCELED ? A2aTaskStatus.CANCELED : A2aTaskStatus.FAILED,
                current.createdAt(),
                now,
                current.requestMessage(),
                current.responsePayload(),
                current.status() == A2aTaskStatus.CANCELED ? current.errorCode() : (errorCode == null ? "INTERNAL_ERROR" : errorCode),
                current.status() == A2aTaskStatus.CANCELED ? current.errorMessage() : (errorMessage == null ? "A2A task failed" : errorMessage)
        );
    }

    /**
     * CANCELED 상태로 전이한다.
     */
    static A2aTaskSnapshot cancel(A2aTaskSnapshot current, String reason, Instant now) {
        return new A2aTaskSnapshot(
                current.taskId(),
                current.scopeName(),
                current.sessionId(),
                A2aTaskStatus.CANCELED,
                current.createdAt(),
                now,
                current.requestMessage(),
                current.responsePayload(),
                "CANCELED",
                reason == null || reason.isBlank() ? "Canceled by request" : reason
        );
    }
}
