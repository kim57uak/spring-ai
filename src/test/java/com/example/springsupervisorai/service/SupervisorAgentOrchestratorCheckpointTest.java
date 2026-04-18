package com.example.springsupervisorai.service;

import com.example.springsupervisorai.a2a.lifecycle.SupervisorA2aLifecycleService;
import com.example.springsupervisorai.service.agent.graph.SupervisorStateGraphFactory;
import com.example.springsupervisorai.service.agent.invoke.A2AInvocationService;
import com.example.springsupervisorai.service.agent.store.ConversationStore;
import com.example.springsupervisorai.service.agent.store.GraphCheckpointStore;
import com.example.springsupervisorai.service.agent.swarm.SupervisorSwarmCoordinator;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SupervisorAgentOrchestratorCheckpointTest {

    @Test
    void resolveCheckpointIdShouldClearInvalidPayload() {
        GraphCheckpointStore checkpointStore = mock(GraphCheckpointStore.class);
        SupervisorExecutionStateLoader stateLoader = newStateLoader(checkpointStore);
        when(checkpointStore.loadCheckpoint("s1")).thenReturn(Optional.of("invalid-checkpoint"));

        String resolved = stateLoader.resolveCheckpointId("s1");

        assertThat(resolved).isEmpty();
        verify(checkpointStore).clear("s1");
    }

    @Test
    void resolveCheckpointIdShouldAcceptValidPayload() {
        GraphCheckpointStore checkpointStore = mock(GraphCheckpointStore.class);
        SupervisorExecutionStateLoader stateLoader = newStateLoader(checkpointStore);
        String payload = "state=COMPLETED;at=2026-04-11T00:00:00Z";
        when(checkpointStore.loadCheckpoint("s2")).thenReturn(Optional.of(payload));

        String resolved = stateLoader.resolveCheckpointId("s2");

        assertThat(resolved).isEqualTo(payload);
        verify(checkpointStore, never()).clear("s2");
    }

    private SupervisorExecutionStateLoader newStateLoader(GraphCheckpointStore checkpointStore) {
        ConversationStore conversationStore = mock(ConversationStore.class);
        SupervisorSwarmCoordinator swarmCoordinator = mock(SupervisorSwarmCoordinator.class);
        return new SupervisorExecutionStateLoader(conversationStore, checkpointStore, swarmCoordinator);
    }
}
