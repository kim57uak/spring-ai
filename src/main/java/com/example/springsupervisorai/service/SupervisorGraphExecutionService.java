package com.example.springsupervisorai.service;

import com.example.springsupervisorai.model.SupervisorAgentRequest;
import com.example.springsupervisorai.model.SupervisorGraphState;
import com.example.springsupervisorai.model.SupervisorPlanningContext;
import com.example.springsupervisorai.model.SupervisorRuntimeState;
import com.example.springsupervisorai.model.SwarmState;
import com.example.springsupervisorai.service.agent.graph.SupervisorGraphInputBuilder;
import com.example.springsupervisorai.service.agent.graph.SupervisorStateGraphFactory;
import com.example.springsupervisorai.service.agent.swarm.SupervisorSwarmCoordinator;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.RunnableConfig;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Supervisor graph 실행 준비와 invoke를 담당한다.
 */
@Service
public class SupervisorGraphExecutionService {

    @FunctionalInterface
    public interface ProgressCallback {
        void emit(String stage, int progress, String message, Map<String, Object> metadata);
    }

    public record GraphExecutionResult(
            SupervisorGraphState state,
            SupervisorPlanningContext context,
            List<String> history,
            Optional<SwarmState> latestSwarm,
            String checkpointId
    ) {
    }

    private final SupervisorStateGraphFactory graphFactory;
    private final SupervisorExecutionStateLoader stateLoader;
    private final SupervisorProgressPublisher progressPublisher;
    private final SupervisorGraphInputBuilder graphInputBuilder;

    public SupervisorGraphExecutionService(
            SupervisorStateGraphFactory graphFactory,
            SupervisorExecutionStateLoader stateLoader,
            SupervisorProgressPublisher progressPublisher,
            SupervisorGraphInputBuilder graphInputBuilder
    ) {
        this.graphFactory = graphFactory;
        this.stateLoader = stateLoader;
        this.progressPublisher = progressPublisher;
        this.graphInputBuilder = graphInputBuilder;
    }

    public GraphExecutionResult execute(
            SupervisorAgentRequest request,
            String taskId,
            AtomicBoolean canceled,
            ProgressCallback progressCallback,
            java.util.function.BooleanSupplier cancellationChecker
    ) {
        throwIfCanceled(canceled, cancellationChecker);

        progressCallback.emit(SupervisorProgressSupport.STAGE_ANALYZING, 10, "질문 의도 분석을 시작합니다.", Map.of());
        SupervisorExecutionStateLoader.LoadedState loadedState = stateLoader.load(request.sessionId());
        List<String> history = loadedState.history();
        throwIfCanceled(canceled, cancellationChecker);

        progressCallback.emit(SupervisorProgressSupport.STAGE_ANALYZING, 20, "히스토리 로드 완료", Map.of(
                "historyCount", history.size()
        ));

        progressCallback.emit(SupervisorProgressSupport.STAGE_SWARM, 22, "Swarm 상태를 조회합니다.", Map.of(
                "sessionId", shortSessionId(request.sessionId())
        ));
        Optional<SwarmState> latestSwarm = loadedState.latestSwarm();
        Map<String, Object> swarmFacts = loadedState.swarmFacts();
        long swarmStateVersion = loadedState.swarmStateVersion();
        progressCallback.emit(SupervisorProgressSupport.STAGE_SWARM, 25, "Swarm 상태 로드 완료", Map.of(
                "swarmFound", latestSwarm.isPresent(),
                "swarmStateVersion", swarmStateVersion,
                "swarmFactCount", swarmFacts.size()
        ));
        progressPublisher.recordProgress(
                taskId,
                request.sessionId(),
                "GRAPH",
                SupervisorProgressSupport.STAGE_GRAPH,
                25,
                "Graph execution started",
                Map.of(
                        "historyCount", history.size(),
                        "swarmStateVersion", swarmStateVersion
                )
        );

        String checkpointId = loadedState.checkpointId();
        progressCallback.emit(SupervisorProgressSupport.STAGE_PLANNING, 30, "라우팅 계획을 수립하고 있습니다...", Map.of(
                "hasCheckpoint", !checkpointId.isBlank()
        ));

        CompiledGraph<SupervisorGraphState> graph = graphFactory.getCompiledGraph();
        RunnableConfig.Builder configBuilder = RunnableConfig.builder().threadId(request.sessionId());
        if (!checkpointId.isBlank()) {
            configBuilder.checkPointId(checkpointId);
        }

        progressCallback.emit(SupervisorProgressSupport.STAGE_PLANNING, 35,
                "Supervisor 그래프를 실행합니다. (Planning → Invoking → Merging)",
                Map.of("graphNodes", "PLAN, INVOKE, MERGE"));

        Map<String, Object> graphInput = graphInputBuilder.buildInitialInput(request, taskId, loadedState);

        progressCallback.emit(SupervisorProgressSupport.STAGE_GRAPH, 36, "그래프 입력 파라미터 설정 완료", Map.of(
                "sessionId", shortSessionId(request.sessionId()),
                "model", request.model() == null ? "openai" : request.model(),
                "historySize", history.size(),
                "startNode", SupervisorRuntimeState.HISTORY_LOADED.value()
        ));

        progressCallback.emit(SupervisorProgressSupport.STAGE_GRAPH, 37, "→ PLAN 노드 실행 예정 (라우팅 계획 수립)", Map.of(
                "nodeType", "PLAN",
                "agent", "plannerAgent",
                "input", "userMessage + history"
        ));

        SupervisorGraphState state = graph.invoke(graphInput, configBuilder.build())
                .orElseGet(() -> new SupervisorGraphState(graphInput));
        SupervisorPlanningContext context = state.toPlanningContext();
        throwIfCanceled(canceled, cancellationChecker);
        return new GraphExecutionResult(state, context, history, latestSwarm, checkpointId);
    }

    private void throwIfCanceled(AtomicBoolean canceled, java.util.function.BooleanSupplier cancellationChecker) {
        if (canceled.get() || cancellationChecker.getAsBoolean()) {
            throw new CancellationException("Supervisor task canceled");
        }
    }

    private String shortSessionId(String sessionId) {
        String value = sessionId == null ? "" : sessionId.trim();
        if (value.length() <= 10) {
            return value;
        }
        return value.substring(0, 10) + "...";
    }
}
