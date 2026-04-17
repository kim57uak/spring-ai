package com.example.springsupervisorai.service;

import com.example.springsupervisorai.a2a.lifecycle.SupervisorA2aLifecycleService;
import com.example.springsupervisorai.model.SupervisorAgentRequest;
import com.example.springsupervisorai.model.SupervisorErrorCode;
import com.example.springsupervisorai.model.SupervisorOutputEvent;
import com.example.springsupervisorai.service.agent.graph.SupervisorBatchExecutionPolicy;
import com.example.springsupervisorai.service.agent.graph.SupervisorGraphInputBuilder;
import com.example.springsupervisorai.service.agent.graph.SupervisorPlanRunner;
import com.example.springsupervisorai.service.agent.compose.SupervisorResponseComposeService;
import com.example.springsupervisorai.service.agent.invoke.A2AInvocationService;
import com.example.springsupervisorai.service.agent.store.ConversationStore;
import com.example.springsupervisorai.service.agent.store.GraphCheckpointStore;
import com.example.springsupervisorai.service.agent.swarm.SupervisorSwarmCoordinator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import reactor.core.publisher.Flux;

/**
 * {@link SupervisorAgentOrchestrator#execute(SupervisorAgentRequest, String)} 회귀 테스트.
 * <p>
 * 리팩토링 전후의 핵심 계약(성공 시 완료 처리, compose 실패 시 실패 처리)을 검증한다.
 */
class SupervisorAgentOrchestratorExecuteTest {

    /**
     * compose 성공 시 task가 완료 상태로 반영되는지 검증한다.
     */
    @Test
    void executeShouldMarkCompletedWhenComposeSucceeds() {
        Fixture fixture = new Fixture();
        when(fixture.composeService.streamComposeEvents(any())).thenReturn(Flux.just(SupervisorOutputEvent.text("answer")));
        SupervisorAgentOrchestrator orchestrator = fixture.newOrchestrator();

        List<String> chunks = orchestrator.execute(new SupervisorAgentRequest("s1", "hello", "openai"), "task-1")
                .collectList()
                .blockOptional()
                .orElse(List.of());

        assertThat(chunks).isNotEmpty();
        verify(fixture.lifecycleService).markCompleted("task-1", "answer");
        verify(fixture.lifecycleService, never()).markFailed(eq("task-1"), eq(SupervisorErrorCode.COMPOSE_ERROR.value()), any());
    }

    /**
     * compose 실패 시 compose 에러 코드로 task 실패가 기록되는지 검증한다.
     */
    @Test
    void executeShouldMarkComposeFailureWhenComposeErrors() {
        Fixture fixture = new Fixture();
        when(fixture.composeService.streamComposeEvents(any())).thenReturn(Flux.error(new IllegalStateException("compose-boom")));
        SupervisorAgentOrchestrator orchestrator = fixture.newOrchestrator();

        List<String> chunks = orchestrator.execute(new SupervisorAgentRequest("s2", "hello", "openai"), "task-2")
                .collectList()
                .blockOptional()
                .orElse(List.of());

        assertThat(chunks).anyMatch(line -> line.contains("응답 합성 중 오류가 발생했습니다."));
        verify(fixture.lifecycleService).markFailed(eq("task-2"), eq(SupervisorErrorCode.COMPOSE_ERROR.value()), contains("compose-boom"));
        verify(fixture.lifecycleService, never()).markCompleted(eq("task-2"), any());
    }

    /**
     * 오케스트레이터 테스트용 의존성 fixture.
     */
    private static final class Fixture {

        private final ConversationStore conversationStore = mock(ConversationStore.class);
        private final GraphCheckpointStore checkpointStore = mock(GraphCheckpointStore.class);
        private final SupervisorResponseComposeService composeService = mock(SupervisorResponseComposeService.class);
        private final SupervisorA2aLifecycleService lifecycleService = mock(SupervisorA2aLifecycleService.class);
        private final A2AInvocationService invocationService = mock(A2AInvocationService.class);
        private final SupervisorSwarmCoordinator swarmCoordinator = mock(SupervisorSwarmCoordinator.class);
        private final SupervisorProgressPublisher progressPublisher = new SupervisorProgressPublisher(swarmCoordinator);
        private final SupervisorExecutionPersistenceService persistenceService =
                new SupervisorExecutionPersistenceService(conversationStore, checkpointStore, lifecycleService);
        private final SupervisorFallbackInvokeService fallbackInvokeService =
                new SupervisorFallbackInvokeService(
                        new SupervisorBatchExecutionPolicy(new com.example.springsupervisorai.config.A2aSupervisorRoutingProperties()),
                        new SupervisorPlanRunner(invocationService),
                        swarmCoordinator
                );
        private final com.example.springsupervisorai.service.agent.graph.SupervisorStateGraphFactory graphFactory =
                mock(com.example.springsupervisorai.service.agent.graph.SupervisorStateGraphFactory.class);
        private final SupervisorExecutionStateLoader stateLoader =
                new SupervisorExecutionStateLoader(conversationStore, checkpointStore, swarmCoordinator);
        private final SupervisorGraphExecutionService graphExecutionService =
                new SupervisorGraphExecutionService(graphFactory, stateLoader, progressPublisher, new SupervisorGraphInputBuilder());
        private final SupervisorExceptionTranslator exceptionTranslator = new SupervisorExceptionTranslator();
        private final SupervisorExecutionSummaryEmitter executionSummaryEmitter =
                new SupervisorExecutionSummaryEmitter(new SupervisorHandoffProgressSupport());

        /**
         * 기본 mock 동작을 주입해 테스트 가능한 오케스트레이터 인스턴스를 생성한다.
         *
         * @return 오케스트레이터 테스트 인스턴스
         */
        private SupervisorAgentOrchestrator newOrchestrator() {
            when(conversationStore.load(any())).thenReturn(List.of("user: prev"));
            when(lifecycleService.get(any())).thenReturn(Optional.empty());
            @SuppressWarnings("unchecked")
            org.bsc.langgraph4j.CompiledGraph<com.example.springsupervisorai.model.SupervisorGraphState> graph =
                    (org.bsc.langgraph4j.CompiledGraph<com.example.springsupervisorai.model.SupervisorGraphState>) mock(org.bsc.langgraph4j.CompiledGraph.class);
            when(checkpointStore.loadCheckpoint(any())).thenReturn(Optional.empty());
            when(graphFactory.getCompiledGraph()).thenReturn(graph);
            when(graph.invoke(anyMap(), any())).thenReturn(Optional.empty());
            when(swarmCoordinator.loadLatestBySession(any())).thenReturn(Optional.empty());
            return new SupervisorAgentOrchestrator(
                    composeService,
                    lifecycleService,
                    invocationService,
                    progressPublisher,
                    persistenceService,
                    fallbackInvokeService,
                    graphExecutionService,
                    exceptionTranslator,
                    executionSummaryEmitter
            );
        }
    }
}
