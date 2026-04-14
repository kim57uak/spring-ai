package com.example.springsupervisorai.a2a.task;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class A2aTaskSnapshotTransitionsTest {

    @Test
    void markCompletedReturnsSameSnapshotWhenCanceled() {
        A2aTaskSnapshot canceled = new A2aTaskSnapshot(
                "sup-task-1",
                "s-1",
                A2aTaskStatus.CANCELED,
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:03:00Z"),
                "request",
                "payload",
                "CANCELED",
                "manual-cancel"
        );

        A2aTaskSnapshot next = A2aTaskSnapshotTransitions.markCompleted(
                canceled,
                "new-payload",
                Instant.parse("2026-01-01T00:10:00Z")
        );

        assertThat(next).isSameAs(canceled);
    }

    @Test
    void markWaitingReviewUsesDefaultReasonWhenNull() {
        Instant now = Instant.parse("2026-01-01T00:10:00Z");
        A2aTaskSnapshot submitted = new A2aTaskSnapshot(
                "sup-task-2",
                "s-2",
                A2aTaskStatus.SUBMITTED,
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:01:00Z"),
                "request",
                "",
                "",
                ""
        );

        A2aTaskSnapshot next = A2aTaskSnapshotTransitions.markWaitingReview(submitted, null, now);

        assertThat(next.status()).isEqualTo(A2aTaskStatus.WAITING_REVIEW);
        assertThat(next.errorCode()).isEqualTo("HITL_REQUIRED");
        assertThat(next.errorMessage()).isEqualTo("Human review is required");
        assertThat(next.updatedAt()).isEqualTo(now);
    }
}
