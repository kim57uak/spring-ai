package com.example.springsupervisorai.service;

import com.example.springsupervisorai.model.SupervisorOutputEvent;
import com.example.springsupervisorai.model.SupervisorOutputEventType;
import com.example.springsupervisorai.service.agent.swarm.SupervisorSwarmCoordinator;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Sinks;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * {@link SupervisorProgressPublisher}의 user/event-log 동시 기록 테스트.
 */
class SupervisorProgressPublisherTest {

    @Test
    void emitEventShouldPublishUserProgressAndRecordSwarmLog() {
        SupervisorSwarmCoordinator swarmCoordinator = mock(SupervisorSwarmCoordinator.class);
        SupervisorProgressPublisher publisher = new SupervisorProgressPublisher(swarmCoordinator);
        Sinks.Many<SupervisorOutputEvent> sink = Sinks.many().replay().all();

        publisher.emitEvent(
                sink,
                "task-1",
                "session-1",
                "PLAN",
                SupervisorProgressSupport.STAGE_PLANNING,
                40,
                "planned",
                Map.of("count", 2)
        );

        List<SupervisorOutputEvent> events = sink.asFlux().take(1).collectList().block();

        assertThat(events).hasSize(1);
        assertThat(events.get(0).type()).isEqualTo(SupervisorOutputEventType.PROGRESS);
        assertThat(events.get(0).progressEvent().stage()).isEqualTo(SupervisorProgressSupport.STAGE_PLANNING);
        verify(swarmCoordinator).recordNodeEvent("task-1", "session-1", "PLAN", "planned", Map.of(
                "stage", SupervisorProgressSupport.STAGE_PLANNING,
                "progress", 40,
                "count", 2
        ));
    }
}
