package com.example.springsupervisorai.service.agent.handoff;

import com.example.springsupervisorai.config.A2aSupervisorRoutingProperties;
import com.example.springsupervisorai.model.DownstreamCallResult;
import com.example.springsupervisorai.model.HandoffValidationResult;
import com.example.springsupervisorai.model.SupervisorPlanningContext;
import com.example.springsupervisorai.service.agent.invoke.DownstreamAgentCardCache;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultHandoffPolicyServiceTest {

    @Test
    void evaluateShouldRejectWhenFeatureFlagIsDisabled() {
        A2aSupervisorRoutingProperties properties = baseProperties();
        properties.getHandoff().setEnabled(false);
        DownstreamAgentCardCache cardCache = mock(DownstreamAgentCardCache.class);

        DefaultHandoffPolicyService service = new DefaultHandoffPolicyService(properties, cardCache);
        SupervisorPlanningContext context = new SupervisorPlanningContext("s1", "테스트", "openai");
        DownstreamCallResult result = new DownstreamCallResult(
                "search", "", "COMPLETED", "{}", "", "",
                true, "product", "message/send", "delegate", Map.of("q", "camera")
        );

        List<HandoffValidationResult> validations = service.evaluate(context, List.of(result));

        assertThat(validations).hasSize(1);
        assertThat(validations.get(0).accepted()).isFalse();
        assertThat(validations.get(0).reasonCode()).isEqualTo(DefaultHandoffPolicyService.REASON_FLAG_DISABLED);
    }

    @Test
    void evaluateShouldRejectStreamingMethodWhenAgentDoesNotSupportStreaming() {
        A2aSupervisorRoutingProperties properties = baseProperties();
        properties.getHandoff().setEnabled(true);
        DownstreamAgentCardCache cardCache = mock(DownstreamAgentCardCache.class);
        when(cardCache.supportsStreaming("product")).thenReturn(false);

        DefaultHandoffPolicyService service = new DefaultHandoffPolicyService(properties, cardCache);
        SupervisorPlanningContext context = new SupervisorPlanningContext("s1", "테스트", "openai");
        DownstreamCallResult result = new DownstreamCallResult(
                "search", "", "COMPLETED", "{}", "", "",
                true, "product", "message/stream", "delegate", Map.of()
        );

        List<HandoffValidationResult> validations = service.evaluate(context, List.of(result));

        assertThat(validations).hasSize(1);
        assertThat(validations.get(0).accepted()).isFalse();
        assertThat(validations.get(0).reasonCode()).isEqualTo(DefaultHandoffPolicyService.REASON_STREAM_NOT_SUPPORTED);
    }

    @Test
    void evaluateShouldAcceptValidHandoffDirective() {
        A2aSupervisorRoutingProperties properties = baseProperties();
        properties.getHandoff().setEnabled(true);
        DownstreamAgentCardCache cardCache = mock(DownstreamAgentCardCache.class);

        DefaultHandoffPolicyService service = new DefaultHandoffPolicyService(properties, cardCache);
        SupervisorPlanningContext context = new SupervisorPlanningContext("s1", "테스트", "openai");
        context.setSwarmSharedFacts(Map.of("handoffHopCount", 1, "handoffPath", List.of("search")));
        DownstreamCallResult result = new DownstreamCallResult(
                "search", "", "COMPLETED", "{}", "", "",
                true, "product", "message/send", "delegate", Map.of("query", "camera")
        );

        List<HandoffValidationResult> validations = service.evaluate(context, List.of(result));

        assertThat(validations).hasSize(1);
        HandoffValidationResult validation = validations.get(0);
        assertThat(validation.accepted()).isTrue();
        assertThat(validation.plan()).isNotNull();
        assertThat(validation.plan().agentKey()).isEqualTo("product");
        assertThat(validation.plan().isHandoff()).isTrue();
    }

    @Test
    void evaluateShouldRejectWhenRateLimitIsExceeded() {
        A2aSupervisorRoutingProperties properties = baseProperties();
        properties.getHandoff().setEnabled(true);
        properties.getHandoff().setMaxPerMinute(1);
        DownstreamAgentCardCache cardCache = mock(DownstreamAgentCardCache.class);

        DefaultHandoffPolicyService service = new DefaultHandoffPolicyService(properties, cardCache);
        SupervisorPlanningContext context = new SupervisorPlanningContext("s1", "테스트", "openai");
        context.setSwarmSharedFacts(Map.of(
                "handoffWindowStartEpochMs", System.currentTimeMillis(),
                "handoffWindowCount", 1
        ));
        DownstreamCallResult result = new DownstreamCallResult(
                "search", "", "COMPLETED", "{}", "", "",
                true, "product", "message/send", "delegate", Map.of()
        );

        List<HandoffValidationResult> validations = service.evaluate(context, List.of(result));

        assertThat(validations).hasSize(1);
        assertThat(validations.get(0).accepted()).isFalse();
        assertThat(validations.get(0).reasonCode()).isEqualTo(DefaultHandoffPolicyService.REASON_RATE_LIMIT);
    }

    @Test
    void evaluateShouldRejectWhenHopLimitIsReached() {
        A2aSupervisorRoutingProperties properties = baseProperties();
        properties.getHandoff().setEnabled(true);
        properties.getHandoff().setMaxHops(1);
        DownstreamAgentCardCache cardCache = mock(DownstreamAgentCardCache.class);

        DefaultHandoffPolicyService service = new DefaultHandoffPolicyService(properties, cardCache);
        SupervisorPlanningContext context = new SupervisorPlanningContext("s1", "테스트", "openai");
        context.setSwarmSharedFacts(Map.of("handoffHopCount", 1, "handoffPath", List.of("search")));
        DownstreamCallResult result = new DownstreamCallResult(
                "search", "", "COMPLETED", "{}", "", "",
                true, "product", "message/send", "delegate", Map.of()
        );

        List<HandoffValidationResult> validations = service.evaluate(context, List.of(result));

        assertThat(validations).hasSize(1);
        assertThat(validations.get(0).accepted()).isFalse();
        assertThat(validations.get(0).reasonCode()).isEqualTo(DefaultHandoffPolicyService.REASON_HOP_LIMIT);
    }

    @Test
    void evaluateShouldRejectWhenRecentPathContainsSameAgent() {
        A2aSupervisorRoutingProperties properties = baseProperties();
        properties.getHandoff().setEnabled(true);
        properties.getHandoff().setBlockSameAgentWithinSteps(2);
        DownstreamAgentCardCache cardCache = mock(DownstreamAgentCardCache.class);

        DefaultHandoffPolicyService service = new DefaultHandoffPolicyService(properties, cardCache);
        SupervisorPlanningContext context = new SupervisorPlanningContext("s1", "테스트", "openai");
        context.setSwarmSharedFacts(Map.of("handoffHopCount", 0, "handoffPath", List.of("search", "product")));
        DownstreamCallResult result = new DownstreamCallResult(
                "reservation", "", "COMPLETED", "{}", "", "",
                true, "product", "message/send", "delegate", Map.of()
        );

        List<HandoffValidationResult> validations = service.evaluate(context, List.of(result));

        assertThat(validations).hasSize(1);
        assertThat(validations.get(0).accepted()).isFalse();
        assertThat(validations.get(0).reasonCode()).isEqualTo(DefaultHandoffPolicyService.REASON_DUPLICATE_PATH);
    }

    private A2aSupervisorRoutingProperties baseProperties() {
        A2aSupervisorRoutingProperties properties = new A2aSupervisorRoutingProperties();
        A2aSupervisorRoutingProperties.Route productRoute = new A2aSupervisorRoutingProperties.Route();
        productRoute.setEndpoint("http://localhost:8082/a2a/product");
        productRoute.setMethod("message/send");
        A2aSupervisorRoutingProperties.Route searchRoute = new A2aSupervisorRoutingProperties.Route();
        searchRoute.setEndpoint("http://localhost:8082/a2a/search");
        searchRoute.setMethod("message/send");
        properties.setRouting(Map.of("product", productRoute, "search", searchRoute));
        return properties;
    }
}
