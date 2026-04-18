package com.example.springsupervisorai.service.agent.swarm;

import com.example.springsupervisorai.model.RoutingPlan;
import com.example.springsupervisorai.service.agent.store.SupervisorSwarmStateStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class DefaultSupervisorSwarmCoordinatorTest {

    @Test
    void applyRoutingRuleShouldSkipPersistenceWhenTaskIdIsBlank() {
        SupervisorSwarmStateStore swarmStateStore = mock(SupervisorSwarmStateStore.class);
        DefaultSupervisorSwarmCoordinator coordinator = new DefaultSupervisorSwarmCoordinator(swarmStateStore);

        RoutingPlan reservationPlan = new RoutingPlan("reservation", "SendMessage", "reservation route", 1, Map.of());

        List<RoutingPlan> routed = coordinator.applyRoutingRule(
                "",
                "session-1",
                List.of(reservationPlan),
                Map.of("agentCooldownUntilEpochMs", Map.of("reservation", System.currentTimeMillis() + 60_000))
        );

        assertThat(routed).containsExactly(reservationPlan);
        verifyNoInteractions(swarmStateStore);
    }
}
