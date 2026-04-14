package com.example.springsupervisorai.a2a.task;

import com.example.springsupervisorai.model.SupervisorErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryA2ATaskStoreTest {

    @Test
    void markWaitingReviewShouldUseDefaultReasonWhenNull() {
        InMemoryA2ATaskStore store = new InMemoryA2ATaskStore();
        A2aTaskSnapshot created = store.create("session-1", "request");

        A2aTaskSnapshot waiting = store.markWaitingReview(created.taskId(), null).orElseThrow();

        assertThat(waiting.status()).isEqualTo(A2aTaskStatus.WAITING_REVIEW);
        assertThat(waiting.errorCode()).isEqualTo("HITL_REQUIRED");
        assertThat(waiting.errorMessage()).isEqualTo("Human review is required");
    }

    @Test
    void markCompletedShouldKeepCanceledState() {
        InMemoryA2ATaskStore store = new InMemoryA2ATaskStore();
        A2aTaskSnapshot created = store.create("session-2", "request");

        store.cancel(created.taskId(), "manual-cancel");
        A2aTaskSnapshot completed = store.markCompleted(created.taskId(), "new-payload").orElseThrow();

        assertThat(completed.status()).isEqualTo(A2aTaskStatus.CANCELED);
        assertThat(completed.errorCode()).isEqualTo(SupervisorErrorCode.CANCELED.value());
        assertThat(completed.responsePayload()).isEqualTo("");
    }
}
