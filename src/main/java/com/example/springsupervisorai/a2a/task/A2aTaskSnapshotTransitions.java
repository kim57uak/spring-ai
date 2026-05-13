package com.example.springsupervisorai.a2a.task;

import com.example.springsupervisorai.model.SupervisorErrorCode;

import java.time.Instant;

/**
 * Supervisor A2A task 스냅샷 상태 전이 유틸리티.
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
                current.sessionId(),
                A2aTaskStatus.WORKING,
                current.createdAt(),
                now,
                current.requestMessage(),
                current.responsePayload(),
                current.errorCode(),
                current.errorMessage(),
                current.contextId()
        );
    }

    /**
     * WAITING_REVIEW 상태로 전이한다.
     */
    static A2aTaskSnapshot markWaitingReview(A2aTaskSnapshot current, String reason, Instant now) {
        return new A2aTaskSnapshot(
                current.taskId(),
                current.sessionId(),
                A2aTaskStatus.WAITING_REVIEW,
                current.createdAt(),
                now,
                current.requestMessage(),
                current.responsePayload(),
                "HITL_REQUIRED",
                reason == null ? "Human review is required" : reason,
                current.contextId()
        );
    }

    /**
     * COMPLETED 상태로 전이한다.
     * <p>
     * 이미 CANCELED 상태인 task는 변경하지 않는다.
     */
    static A2aTaskSnapshot markCompleted(A2aTaskSnapshot current, String responsePayload, Instant now) {
        if (current.status() == A2aTaskStatus.CANCELED) {
            return current;
        }
        return new A2aTaskSnapshot(
                current.taskId(),
                current.sessionId(),
                A2aTaskStatus.COMPLETED,
                current.createdAt(),
                now,
                current.requestMessage(),
                responsePayload == null ? "" : responsePayload,
                "",
                "",
                current.contextId()
        );
    }

    /**
     * FAILED 상태로 전이한다.
     * <p>
     * 이미 CANCELED 상태인 task는 변경하지 않는다.
     */
    static A2aTaskSnapshot markFailed(A2aTaskSnapshot current, String errorCode, String errorMessage, Instant now) {
        if (current.status() == A2aTaskStatus.CANCELED) {
            return current;
        }
        return new A2aTaskSnapshot(
                current.taskId(),
                current.sessionId(),
                A2aTaskStatus.FAILED,
                current.createdAt(),
                now,
                current.requestMessage(),
                current.responsePayload(),
                errorCode == null ? SupervisorErrorCode.INTERNAL_ERROR.value() : errorCode,
                errorMessage == null ? "Supervisor task failed" : errorMessage,
                current.contextId()
        );
    }

    /**
     * CANCELED 상태로 전이한다.
     */
    static A2aTaskSnapshot cancel(A2aTaskSnapshot current, String reason, Instant now) {
        return new A2aTaskSnapshot(
                current.taskId(),
                current.sessionId(),
                A2aTaskStatus.CANCELED,
                current.createdAt(),
                now,
                current.requestMessage(),
                current.responsePayload(),
                SupervisorErrorCode.CANCELED.value(),
                reason == null || reason.isBlank() ? "Canceled by request" : reason,
                current.contextId()
        );
    }

    /**
     * 요청 메시지를 갱신한다.
     */
    static A2aTaskSnapshot updateTaskMessage(A2aTaskSnapshot current, String newMessage, Instant now) {
        return new A2aTaskSnapshot(
                current.taskId(),
                current.sessionId(),
                current.status(),
                current.createdAt(),
                now,
                newMessage == null ? "" : newMessage,
                current.responsePayload(),
                current.errorCode(),
                current.errorMessage(),
                current.contextId()
        );
    }
}
