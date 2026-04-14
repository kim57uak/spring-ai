package com.example.springai.a2a.task;

import com.example.springai.model.agent.AgentScopeName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class A2aTaskSnapshotTransitionsTest {

    @Test
    void markCompletedKeepsCanceledStateAndPayload() {
        Instant created = Instant.parse("2026-01-01T00:00:00Z");
        Instant updated = Instant.parse("2026-01-01T00:05:00Z");
        Instant now = Instant.parse("2026-01-01T00:10:00Z");
        A2aTaskSnapshot canceled = new A2aTaskSnapshot(
                "task-1",
                AgentScopeName.SEARCH,
                "s-1",
                A2aTaskStatus.CANCELED,
                created,
                updated,
                "request",
                "keep-response",
                "CANCELED",
                "keep-message"
        );

        A2aTaskSnapshot next = A2aTaskSnapshotTransitions.markCompleted(canceled, "new-response", now);

        assertThat(next.status()).isEqualTo(A2aTaskStatus.CANCELED);
        assertThat(next.responsePayload()).isEqualTo("keep-response");
        assertThat(next.errorCode()).isEqualTo("CANCELED");
        assertThat(next.errorMessage()).isEqualTo("keep-message");
        assertThat(next.updatedAt()).isEqualTo(now);
    }

    @Test
    void markFailedUsesDefaultErrorValuesWhenInputsAreNull() {
        Instant now = Instant.parse("2026-01-01T00:10:00Z");
        A2aTaskSnapshot running = new A2aTaskSnapshot(
                "task-2",
                AgentScopeName.PRODUCT,
                "s-2",
                A2aTaskStatus.RUNNING,
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:01:00Z"),
                "request",
                "response",
                "",
                ""
        );

        A2aTaskSnapshot next = A2aTaskSnapshotTransitions.markFailed(running, null, null, now);

        assertThat(next.status()).isEqualTo(A2aTaskStatus.FAILED);
        assertThat(next.errorCode()).isEqualTo("INTERNAL_ERROR");
        assertThat(next.errorMessage()).isEqualTo("A2A task failed");
        assertThat(next.updatedAt()).isEqualTo(now);
    }
}
