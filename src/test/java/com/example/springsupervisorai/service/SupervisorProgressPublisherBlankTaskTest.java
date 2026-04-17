package com.example.springsupervisorai.service;

import com.example.springsupervisorai.model.SupervisorOutputEvent;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Sinks;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * taskId가 아직 없는 초기 progress 경로의 안전성 테스트.
 */
class SupervisorProgressPublisherBlankTaskTest {

    @Test
    void emitEventShouldSkipSwarmLogWhenTaskIdIsBlank() {
        com.example.springsupervisorai.service.agent.swarm.SupervisorSwarmCoordinator swarmCoordinator =
                mock(com.example.springsupervisorai.service.agent.swarm.SupervisorSwarmCoordinator.class);
        SupervisorProgressPublisher publisher = new SupervisorProgressPublisher(swarmCoordinator);
        Sinks.Many<SupervisorOutputEvent> sink = Sinks.many().replay().all();

        publisher.emitEvent(
                sink,
                "",
                "session-1",
                "INITIALIZING",
                SupervisorProgressSupport.STAGE_INITIALIZING,
                0,
                "요청을 접수했습니다.",
                Map.of("sessionId", "session-1")
        );

        List<SupervisorOutputEvent> events = sink.asFlux().take(1).collectList().block();

        assertThat(events).hasSize(1);
        assertThat(events.get(0).progressEvent().message()).isEqualTo("요청을 접수했습니다.");
        verifyNoInteractions(swarmCoordinator);
    }
}
