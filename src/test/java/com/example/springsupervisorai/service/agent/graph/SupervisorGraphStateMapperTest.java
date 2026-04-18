package com.example.springsupervisorai.service.agent.graph;

import com.example.springsupervisorai.model.DownstreamCallResult;
import com.example.springsupervisorai.model.HandoffDirective;
import com.example.springsupervisorai.model.HandoffValidationResult;
import com.example.springsupervisorai.model.RoutingPlan;
import com.example.springsupervisorai.model.SupervisorGraphSnapshot;
import com.example.springsupervisorai.model.SupervisorGraphState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SupervisorGraphStateMapper} round-trip 계약 테스트.
 */
class SupervisorGraphStateMapperTest {

    @Test
    void snapshotAndStateMapShouldRoundTripTypedState() {
        SupervisorGraphStateMapper mapper = SupervisorGraphStateMapper.INSTANCE;
        SupervisorGraphSnapshot snapshot = new SupervisorGraphSnapshot(
                "task-1",
                "session-1",
                "hello",
                "openai",
                List.of("user: hi"),
                "state=PLANNED;at=2026-04-17T00:00:00Z",
                "PLANNED",
                List.of(new RoutingPlan("product", "message/send", "need product", 1, Map.of("query", "tv"), "HANDOFF", 1, "planner")),
                1,
                List.of(new DownstreamCallResult("product", "d-1", "COMPLETED", "{\"ok\":true}", "", "")),
                List.of(new DownstreamCallResult("product", "d-1", "COMPLETED", "{\"ok\":true}", "", "")),
                List.of(HandoffValidationResult.accepted(
                        new HandoffDirective("planner", "product", "message/send", "validated", Map.of("query", "tv")),
                        new RoutingPlan("product", "message/send", "need product", 1, Map.of("query", "tv"), "HANDOFF", 1, "planner"),
                        1
                )),
                true,
                Map.of("locale", "ko-KR"),
                3L
        );

        Map<String, Object> stateMap = mapper.toStateMap(snapshot);
        SupervisorGraphSnapshot restored = mapper.snapshot(new SupervisorGraphState(stateMap));

        assertThat(restored).usingRecursiveComparison().isEqualTo(snapshot);
    }
}
