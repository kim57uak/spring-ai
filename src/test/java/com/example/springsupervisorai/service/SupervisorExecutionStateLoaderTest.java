package com.example.springsupervisorai.service;

import com.example.springsupervisorai.service.agent.store.ConversationStore;
import com.example.springsupervisorai.service.agent.store.GraphCheckpointStore;
import com.example.springsupervisorai.service.agent.swarm.SupervisorSwarmCoordinator;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SupervisorExecutionStateLoader}의 checkpoint 정리 계약 테스트.
 */
class SupervisorExecutionStateLoaderTest {

    @Test
    void resolveCheckpointIdShouldClearInvalidPayload() {
        ConversationStore conversationStore = mock(ConversationStore.class);
        GraphCheckpointStore checkpointStore = mock(GraphCheckpointStore.class);
        SupervisorSwarmCoordinator swarmCoordinator = mock(SupervisorSwarmCoordinator.class);
        SupervisorExecutionStateLoader loader = new SupervisorExecutionStateLoader(
                conversationStore,
                checkpointStore,
                swarmCoordinator
        );
        when(checkpointStore.loadCheckpoint("session-1")).thenReturn(Optional.of("state=BROKEN;at=not-an-instant"));

        String checkpointId = loader.resolveCheckpointId("session-1");

        assertThat(checkpointId).isEmpty();
        verify(checkpointStore).clear("session-1");
    }
}
