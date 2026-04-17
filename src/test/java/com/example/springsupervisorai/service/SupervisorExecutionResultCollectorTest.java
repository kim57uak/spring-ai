package com.example.springsupervisorai.service;

import com.example.springsupervisorai.model.SupervisorOutputEvent;
import com.example.springsupervisorai.model.SupervisorProgressEvent;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SupervisorExecutionResultCollector} 계약 테스트.
 */
class SupervisorExecutionResultCollectorTest {

    @Test
    void collectShouldIgnoreProgressAndKeepTextPayload() {
        SupervisorExecutionResultCollector collector = new SupervisorExecutionResultCollector();

        SupervisorExecutionResultCollector.SupervisorExecutionResult result = collector.collect(Flux.just(
                SupervisorOutputEvent.progress(new SupervisorProgressEvent("hitl", 5, "progress", Map.of())),
                SupervisorOutputEvent.text("hello "),
                SupervisorOutputEvent.a2ui("{\"view\":\"card\"}"),
                SupervisorOutputEvent.text("world")
        ));

        assertThat(result.textResponse()).isEqualTo("hello world");
        assertThat(result.a2uiPayload()).isEqualTo("{\"view\":\"card\"}");
        assertThat(result.taskPayload()).isEqualTo("hello world");
    }

    @Test
    void collectShouldFallbackToErrorWhenTextIsMissing() {
        SupervisorExecutionResultCollector collector = new SupervisorExecutionResultCollector();

        SupervisorExecutionResultCollector.SupervisorExecutionResult result = collector.collect(Flux.just(
                SupervisorOutputEvent.progress(new SupervisorProgressEvent("compose", 80, "progress", Map.of())),
                SupervisorOutputEvent.error("compose failed")
        ));

        assertThat(result.textResponse()).isEmpty();
        assertThat(result.errorMessage()).isEqualTo("compose failed");
        assertThat(result.taskPayload()).isEqualTo("compose failed");
    }
}
