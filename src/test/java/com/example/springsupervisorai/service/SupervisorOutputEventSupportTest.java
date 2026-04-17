package com.example.springsupervisorai.service;

import com.example.springsupervisorai.model.SupervisorOutputEvent;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SupervisorOutputEventSupport} 직렬화 규칙 테스트.
 */
class SupervisorOutputEventSupportTest {

    @Test
    void serializeShouldKeepTypeSpecificBoundary() {
        String progress = SupervisorOutputEventSupport.serialize(
                SupervisorOutputEvent.progress(SupervisorProgressSupport.event("planning", 40, "planned", Map.of("count", 1)))
        );
        String text = SupervisorOutputEventSupport.serialize(SupervisorOutputEvent.text("answer"));
        String a2ui = SupervisorOutputEventSupport.serialize(SupervisorOutputEvent.a2ui("{\"type\":\"card\"}"));
        String error = SupervisorOutputEventSupport.serialize(SupervisorOutputEvent.error("failed"));

        assertThat(progress).contains("[supervisor] [planning] [40%] planned");
        assertThat(text).isEqualTo("answer");
        assertThat(a2ui).contains("{\"type\":\"card\"}");
        assertThat(error).isEqualTo("failed");
    }
}
