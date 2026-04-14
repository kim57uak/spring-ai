package com.example.springai.a2a.task;

import com.example.springai.model.agent.AgentScopeName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryA2ATaskStoreTest {

    @Test
    void markRunningShouldIgnoreScopeMismatch() {
        InMemoryA2ATaskStore store = new InMemoryA2ATaskStore();
        A2aTaskSnapshot created = store.create(AgentScopeName.SEARCH, "session-1", "hello");

        assertThat(store.markRunning(created.taskId(), AgentScopeName.PRODUCT)).isEmpty();

        A2aTaskSnapshot latest = store.get(created.taskId(), AgentScopeName.SEARCH).orElseThrow();
        assertThat(latest.status()).isEqualTo(A2aTaskStatus.SUBMITTED);
    }

    @Test
    void markCompletedShouldKeepCanceledState() {
        InMemoryA2ATaskStore store = new InMemoryA2ATaskStore();
        A2aTaskSnapshot created = store.create(AgentScopeName.PRODUCT, "session-2", "request");

        store.cancel(created.taskId(), AgentScopeName.PRODUCT, "manual-cancel");
        A2aTaskSnapshot completed = store.markCompleted(created.taskId(), AgentScopeName.PRODUCT, "new-payload")
                .orElseThrow();

        assertThat(completed.status()).isEqualTo(A2aTaskStatus.CANCELED);
        assertThat(completed.responsePayload()).isEqualTo("");
        assertThat(completed.errorCode()).isEqualTo("CANCELED");
    }
}
