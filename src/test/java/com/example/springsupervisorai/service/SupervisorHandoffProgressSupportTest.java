package com.example.springsupervisorai.service;

import com.example.springsupervisorai.model.HandoffDirective;
import com.example.springsupervisorai.model.HandoffValidationResult;
import com.example.springsupervisorai.model.SupervisorGraphSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SupervisorHandoffProgressSupport}의 metadata 계산 규칙 테스트.
 */
class SupervisorHandoffProgressSupportTest {

    @Test
    void metadataShouldPreferAcceptedHandoffValidation() {
        SupervisorHandoffProgressSupport support = new SupervisorHandoffProgressSupport();
        SupervisorGraphSnapshot snapshot = new SupervisorGraphSnapshot(
                "task-1",
                "session-1",
                "hello",
                "openai",
                List.of(),
                "",
                "HANDOFF_APPLIED",
                List.of(),
                0,
                List.of(),
                List.of(),
                List.of(
                        new HandoffValidationResult(false, "FLAG_DISABLED",
                                new HandoffDirective("planner", "product", "message/send", "skip", Map.of()),
                                null, 1),
                        new HandoffValidationResult(true, "ACCEPTED",
                                new HandoffDirective("planner", "reservation", "message/send", "validated", Map.of()),
                                null, 2)
                ),
                true,
                Map.of(),
                0L
        );

        Map<String, Object> metadata = support.metadata(snapshot, 1, 3);

        assertThat(metadata).containsEntry("handoffEnabled", true);
        assertThat(metadata).containsEntry("handoffPlanCount", 1L);
        assertThat(metadata).containsEntry("totalPlanCount", 3);
        assertThat(metadata).containsEntry("fromAgent", "planner");
        assertThat(metadata).containsEntry("toAgent", "reservation");
        assertThat(metadata).containsEntry("reason", "validated");
        assertThat(metadata).containsEntry("hopCount", 2);
    }
}
