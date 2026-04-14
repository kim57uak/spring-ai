package com.example.springsupervisorai.service;

import com.example.springsupervisorai.a2a.lifecycle.SupervisorA2aLifecycleService;
import com.example.springsupervisorai.a2a.task.A2aTaskStatus;
import com.example.springsupervisorai.model.DownstreamCallResult;
import com.example.springsupervisorai.model.RoutingPlan;
import com.example.springsupervisorai.model.SupervisorAgentRequest;
import com.example.springsupervisorai.model.SupervisorErrorCode;
import com.example.springsupervisorai.model.SupervisorGraphState;
import com.example.springsupervisorai.model.SupervisorPlanningContext;
import com.example.springsupervisorai.model.SupervisorRuntimeState;
import com.example.springsupervisorai.model.SwarmState;
import com.example.springsupervisorai.service.agent.compose.SupervisorResponseComposeService;
import com.example.springsupervisorai.service.agent.graph.SupervisorStateGraphFactory;
import com.example.springsupervisorai.service.agent.invoke.A2AInvocationService;
import com.example.springsupervisorai.service.agent.store.ConversationStore;
import com.example.springsupervisorai.service.agent.store.GraphCheckpointStore;
import com.example.springsupervisorai.service.agent.swarm.SupervisorSwarmCoordinator;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.RunnableConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.concurrent.CancellationException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Supervisor 실행 파이프라인의 상위 조정자.
 * <p>
 * 처리 책임:
 * - 그래프 실행을 통한 상태 계산
 * - 필요 시 planner/invoker fallback 실행
 * - compose 스트림 생성/에러 정규화
 * - 세션 히스토리 및 체크포인트 영속화
 * - A2A task 상태 업데이트
 */
@Component
public class SupervisorAgentOrchestrator {

    private static final Logger logger = LoggerFactory.getLogger(SupervisorAgentOrchestrator.class);
    private static final Pattern CHECKPOINT_PATTERN = Pattern.compile("^state=([A-Z_]+);at=(.+)$");
    private static final Set<String> ALLOWED_CHECKPOINT_STATES = Set.of(
            SupervisorRuntimeState.REQUEST_VALIDATED.value(),
            SupervisorRuntimeState.HISTORY_LOADED.value(),
            SupervisorRuntimeState.PLANNED.value(),
            SupervisorRuntimeState.ROUTING_SELECTED.value(),
            SupervisorRuntimeState.A2A_CALLING.value(),
            SupervisorRuntimeState.HANDOFF_EVALUATING.value(),
            SupervisorRuntimeState.HANDOFF_APPLIED.value(),
            SupervisorRuntimeState.HANDOFF_SKIPPED.value(),
            SupervisorRuntimeState.A2A_RESULT_MERGED.value(),
            SupervisorRuntimeState.COMPOSING.value(),
            SupervisorRuntimeState.COMPLETED.value()
    );

    private final ConversationStore conversationStore;
    private final GraphCheckpointStore checkpointStore;
    private final SupervisorStateGraphFactory graphFactory;
    private final SupervisorResponseComposeService composeService;
    private final SupervisorA2aLifecycleService lifecycleService;
    private final A2AInvocationService invocationService;
    private final SupervisorSwarmCoordinator swarmCoordinator;

    /**
     * 오케스트레이터 의존성을 생성자 주입으로 초기화한다.
     *
     * @param conversationStore 세션 히스토리 저장소
     * @param checkpointStore 그래프 체크포인트 저장소
     * @param graphFactory supervisor 상태 그래프 팩토리
     * @param composeService 최종 응답 합성 서비스
     * @param lifecycleService supervisor A2A task 라이프사이클 서비스
     * @param invocationService downstream invocation 포트
     * @param swarmCoordinator swarm 상태 조정 서비스
     */
    public SupervisorAgentOrchestrator(
            ConversationStore conversationStore,
            GraphCheckpointStore checkpointStore,
            SupervisorStateGraphFactory graphFactory,
            SupervisorResponseComposeService composeService,
            SupervisorA2aLifecycleService lifecycleService,
            A2AInvocationService invocationService,
            SupervisorSwarmCoordinator swarmCoordinator
    ) {
        this.conversationStore = conversationStore;
        this.checkpointStore = checkpointStore;
        this.graphFactory = graphFactory;
        this.composeService = composeService;
        this.lifecycleService = lifecycleService;
        this.invocationService = invocationService;
        this.swarmCoordinator = swarmCoordinator;
    }

    /**
     * supervisor 요청을 실행하고 최종 응답 스트림을 반환한다.
     * <p>
     * 실행 순서:
     * 1) 안내 프리페이스 전송
     * 2) 그래프/플래닝/호출 단계 수행
     * 3) compose 스트리밍
     * 4) 종료 시 영속화 + task 상태 반영
     *
     * @param request supervisor 입력 요청
     * @param taskId A2A task 식별자
     * @return 사용자에게 전달할 응답 토큰 스트림
     */
    public Flux<String> execute(SupervisorAgentRequest request, String taskId) {
        AtomicBoolean canceled = new AtomicBoolean(false);
        Sinks.Many<String> progressSink = Sinks.many().multicast().onBackpressureBuffer();

        logger.info("Supervisor execute start taskId={}, sessionId={}, model={}", taskId, request.sessionId(), request.model());

        emitInitialProgress(progressSink, request);

        Flux<String> sharedProgress = progressSink.asFlux().share();
        Mono<SupervisorPlanningContext> planningMono = createPlanningContextMono(request, taskId, canceled, progressSink);
        Flux<String> planningProgress = sharedProgress.takeUntilOther(planningMono);

        return Flux.concat(
                planningProgress,
                planningMono
                        .flatMapMany(context -> composeResponse(request, taskId, canceled, progressSink, context))
                        .onErrorResume(error -> handleOrchestrationError(request, taskId, canceled, progressSink, error))
        ).doOnCancel(() -> {
            canceled.set(true);
            progressSink.tryEmitComplete();
        }).doFinally(signal -> progressSink.tryEmitComplete());
    }

    /**
     * 실행 시작 즉시 초기 진행 메시지를 전송한다.
     *
     * @param progressSink 진행 이벤트 sink
     * @param request supervisor 요청
     */
    private void emitInitialProgress(Sinks.Many<String> progressSink, SupervisorAgentRequest request) {
        emitProgress(progressSink, SupervisorProgressSupport.STAGE_INITIALIZING, 0, "요청을 접수했습니다.", Map.of(
                "sessionId", shortSessionId(request.sessionId()),
                "model", safe(request.model())
        ));
    }

    /**
     * 그래프 실행을 비동기로 수행하는 planning mono를 구성한다.
     *
     * @param request supervisor 요청
     * @param taskId task 식별자
     * @param canceled 취소 플래그
     * @param progressSink 진행 이벤트 sink
     * @return planning context mono
     */
    private Mono<SupervisorPlanningContext> createPlanningContextMono(
            SupervisorAgentRequest request,
            String taskId,
            AtomicBoolean canceled,
            Sinks.Many<String> progressSink
    ) {
        return Mono.fromCallable(
                        () -> invokeGraph(
                                request,
                                taskId,
                                canceled,
                                (stage, progress, message, metadata) -> emitProgress(progressSink, stage, progress, message, metadata)
                        )
                )
                .subscribeOn(Schedulers.boundedElastic())
                .doOnError(error -> emitProgress(progressSink, SupervisorProgressSupport.STAGE_ERROR, 0, "실행 중 오류가 발생했습니다.", Map.of(
                        "error", sanitize(error.getMessage())
                )))
                .cache();
    }

    /**
     * planning context를 바탕으로 compose 스트림을 생성하고 종료 후 상태를 반영한다.
     *
     * @param request supervisor 요청
     * @param taskId task 식별자
     * @param canceled 취소 플래그
     * @param progressSink 진행 이벤트 sink
     * @param context planning context
     * @return 사용자 응답 스트림
     */
    private Flux<String> composeResponse(
            SupervisorAgentRequest request,
            String taskId,
            AtomicBoolean canceled,
            Sinks.Many<String> progressSink,
            SupervisorPlanningContext context
    ) {
        if (isCanceled(taskId, canceled)) {
            progressSink.tryEmitComplete();
            return Flux.empty();
        }

        StringBuilder answer = new StringBuilder();
        AtomicBoolean failed = new AtomicBoolean(false);
        String composingProgress = SupervisorProgressSupport.line(
                SupervisorProgressSupport.STAGE_COMPOSING,
                80,
                "하위 에이전트 실행 결과를 정리하고 답변을 생성합니다...",
                Map.of("resultsCount", context.getResults().size())
        );
        String completedProgress = "\n" + SupervisorProgressSupport.line(
                SupervisorProgressSupport.STAGE_COMPLETED,
                100,
                "응답 생성이 완료되었습니다.",
                Map.of("answerLength", answer.length())
        );

        return Flux.concat(
                Flux.just(composingProgress),
                composeService.streamCompose(context)
                        .onErrorResume(error -> handleComposeError(taskId, canceled, failed, error))
                        .doOnNext(answer::append)
                        .doOnComplete(() -> onComposeCompleted(request, taskId, context, answer, failed))
                        .concatWith(Flux.just(completedProgress))
        );
    }

    /**
     * compose 단계 예외를 정규화하고 task 실패 상태를 반영한다.
     *
     * @param taskId task 식별자
     * @param canceled 취소 플래그
     * @param failed compose 실패 플래그
     * @param error 발생 예외
     * @return 대체 응답 스트림
     */
    private Flux<String> handleComposeError(
            String taskId,
            AtomicBoolean canceled,
            AtomicBoolean failed,
            Throwable error
    ) {
        if (error instanceof CancellationException || isCanceled(taskId, canceled)) {
            return Flux.empty();
        }
        failed.set(true);
        lifecycleService.markFailed(taskId, SupervisorErrorCode.COMPOSE_ERROR.value(), sanitize(error.getMessage()));
        return Flux.concat(
                Flux.just(SupervisorProgressSupport.line(SupervisorProgressSupport.STAGE_ERROR, 0, "응답 합성 중 오류가 발생했습니다.", Map.of(
                        "error", sanitize(error.getMessage())
                ))),
                Flux.just("응답 합성 중 오류가 발생했습니다.")
        );
    }

    /**
     * compose 단계가 정상 종료되면 히스토리/이벤트/task 완료 상태를 기록한다.
     *
     * @param request supervisor 요청
     * @param taskId task 식별자
     * @param context planning context
     * @param answer compose 결과 버퍼
     * @param failed compose 실패 플래그
     */
    private void onComposeCompleted(
            SupervisorAgentRequest request,
            String taskId,
            SupervisorPlanningContext context,
            StringBuilder answer,
            AtomicBoolean failed
    ) {
        persist(context, answer.toString());
        swarmCoordinator.recordNodeEvent(taskId, request.sessionId(), "COMPOSE", "Compose completed", Map.of(
                "answerLength", answer.length(),
                "resultsCount", context.getResults().size()
        ));
        logger.info("Supervisor execute finished taskId={}, sessionId={}, results={}, responseLength={}",
                taskId, request.sessionId(), context.getResults().size(), answer.length());
        if (!failed.get()) {
            lifecycleService.markCompleted(taskId, answer.toString());
        }
    }

    /**
     * 오케스트레이션 단계 예외를 취소/실패 케이스로 구분해 처리한다.
     *
     * @param request supervisor 요청
     * @param taskId task 식별자
     * @param canceled 취소 플래그
     * @param progressSink 진행 이벤트 sink
     * @param error 발생 예외
     * @return 대체 응답 스트림
     */
    private Flux<String> handleOrchestrationError(
            SupervisorAgentRequest request,
            String taskId,
            AtomicBoolean canceled,
            Sinks.Many<String> progressSink,
            Throwable error
    ) {
        if (error instanceof CancellationException || isCanceled(taskId, canceled)) {
            logger.info("Supervisor execute canceled during orchestration taskId={}, sessionId={}", taskId, request.sessionId());
            progressSink.tryEmitComplete();
            return Flux.empty();
        }
        logger.error("Supervisor execute failed taskId={}, sessionId={}, error={}",
                taskId, request.sessionId(), sanitize(error.getMessage()));
        lifecycleService.markFailed(taskId, SupervisorErrorCode.ORCHESTRATION_ERROR.value(), sanitize(error.getMessage()));
        progressSink.tryEmitComplete();
        return Flux.concat(
                Flux.just(SupervisorProgressSupport.line(SupervisorProgressSupport.STAGE_ERROR, 0, "Supervisor 처리 중 오류가 발생했습니다.", Map.of(
                        "error", sanitize(error.getMessage())
                ))),
                Flux.just("Supervisor 처리 중 오류가 발생했습니다.")
        );
    }

    /**
     * 진행 상황을 구조화된 형태로 emit한다.
     *
     * @param sink progress sink
     * @param stage 진행 단계
     * @param progress 진행률 (0-100)
     * @param message 사용자 메시지
     * @param metadata 추가 메타데이터
     */
    private void emitProgress(Sinks.Many<String> sink, String stage, int progress, String message, Map<String, Object> metadata) {
        if (message == null || message.isBlank()) {
            return;
        }
        sink.tryEmitNext(SupervisorProgressSupport.line(stage, progress, message, metadata));
    }

    private String summarizeArguments(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return "{}";
        }
        String raw = String.valueOf(arguments);
        if (raw.length() > 180) {
            return raw.substring(0, 180) + "...";
        }
        return raw;
    }

    private String summarizeResult(DownstreamCallResult result) {
        if (result == null) {
            return "unknown";
        }
        int payloadLength = result.payload() == null ? 0 : result.payload().length();
        String errorCode = safe(result.errorCode()).isBlank() ? "-" : safe(result.errorCode());
        return result.agentKey() + " -> " + result.status() + " (errorCode=" + errorCode + ", payloadLen=" + payloadLength + ")";
    }

    private String shortSessionId(String sessionId) {
        String value = safe(sessionId);
        if (value.length() <= 10) {
            return value;
        }
        return value.substring(0, 10) + "...";
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * 세션 대화 이력과 체크포인트를 초기화한다.
     *
     * @param sessionId 세션 id
     */
    public void clearSession(String sessionId) {
        conversationStore.clear(sessionId);
        checkpointStore.clear(sessionId);
    }

    /**
     * LangGraph 상태 실행을 수행하고 planning context를 복원한다.
     * <p>
     * 그래프 결과가 비어 있는 경우 planner/invoker fallback을 수행해
     * agentic 실행이 중단되지 않도록 보정한다.
     *
     * @param request supervisor 입력 요청
     * @return compose 가능한 planning context
     */
    @FunctionalInterface
    interface ProgressEmitter {
        void emit(String stage, int progress, String message, Map<String, Object> metadata);
    }

    private void progress(ProgressEmitter emitter, String stage, int progress, String message) {
        progress(emitter, stage, progress, message, Map.of());
    }

    private void progress(ProgressEmitter emitter, String stage, int progress, String message, Map<String, Object> metadata) {
        emitter.emit(stage, progress, message, metadata == null ? Map.of() : metadata);
    }

    private SupervisorPlanningContext invokeGraph(
            SupervisorAgentRequest request,
            String taskId,
            AtomicBoolean canceled,
            ProgressEmitter progressEmitter
    ) {
        throwIfCanceled(taskId, canceled);

        // 1단계: 히스토리 로드 (10%)
        progress(progressEmitter, SupervisorProgressSupport.STAGE_ANALYZING, 10, "질문 의도 분석을 시작합니다.");
        List<String> history = conversationStore.load(request.sessionId());
        throwIfCanceled(taskId, canceled);

        progress(progressEmitter, SupervisorProgressSupport.STAGE_ANALYZING, 20, "히스토리 로드 완료", Map.of(
                "historyCount", history.size()
        ));

        progress(progressEmitter, SupervisorProgressSupport.STAGE_SWARM, 22, "Swarm 상태를 조회합니다.", Map.of(
                "sessionId", shortSessionId(request.sessionId())
        ));
        Optional<SwarmState> latestSwarm = swarmCoordinator.loadLatestBySession(request.sessionId());
        Map<String, Object> swarmFacts = latestSwarm.map(SwarmState::sharedFacts).orElse(Map.of());
        long swarmStateVersion = latestSwarm.map(SwarmState::stateVersion).orElse(0L);
        progress(progressEmitter, SupervisorProgressSupport.STAGE_SWARM, 25, "Swarm 상태 로드 완료", Map.of(
                "swarmFound", latestSwarm.isPresent(),
                "swarmStateVersion", swarmStateVersion,
                "swarmFactCount", swarmFacts.size()
        ));
        swarmCoordinator.recordNodeEvent(taskId, request.sessionId(), "GRAPH", "Graph execution started", Map.of(
                "historyCount", history.size(),
                "swarmStateVersion", swarmStateVersion
        ));

        // 2단계: 체크포인트 복원 (30%)
        String checkpointId = resolveCheckpointId(request.sessionId());
        progress(progressEmitter, SupervisorProgressSupport.STAGE_PLANNING, 30, "라우팅 계획을 수립하고 있습니다...", Map.of(
                "hasCheckpoint", !checkpointId.isBlank()
        ));

        CompiledGraph<SupervisorGraphState> graph = graphFactory.getCompiledGraph();

        RunnableConfig.Builder configBuilder = RunnableConfig.builder().threadId(request.sessionId());
        if (!checkpointId.isBlank()) {
            configBuilder.checkPointId(checkpointId);
        }

        // 2.5단계: 그래프 실행 시작 (35%)
        progress(progressEmitter, SupervisorProgressSupport.STAGE_PLANNING, 35, "Supervisor 그래프를 실행합니다. (Planning → Invoking → Merging)", Map.of(
                "graphNodes", "PLAN, INVOKE, MERGE"
        ));

        // 그래프 입력 파라미터 출력
        Map<String, Object> graphInput = Map.ofEntries(
                Map.entry(SupervisorGraphState.TASK_ID, taskId),
                Map.entry(SupervisorGraphState.SESSION_ID, request.sessionId()),
                Map.entry(SupervisorGraphState.USER_MESSAGE, request.message()),
                Map.entry(SupervisorGraphState.MODEL, request.model() == null ? "openai" : request.model()),
                Map.entry(SupervisorGraphState.HISTORY, history),
                Map.entry(SupervisorGraphState.CHECKPOINT_ID, checkpointId),
                Map.entry(SupervisorGraphState.CURRENT_NODE, SupervisorRuntimeState.HISTORY_LOADED.value()),
                Map.entry(SupervisorGraphState.ROUTING_INDEX, 0),
                Map.entry(SupervisorGraphState.ROUTING_PLANS, List.of()),
                Map.entry(SupervisorGraphState.DOWNSTREAM_RESULTS, List.of()),
                Map.entry(SupervisorGraphState.LAST_INVOKE_BATCH_RESULTS, List.of()),
                Map.entry(SupervisorGraphState.HANDOFF_VALIDATIONS, List.of()),
                Map.entry(SupervisorGraphState.HANDOFF_ENABLED, false),
                Map.entry(SupervisorGraphState.SWARM_SHARED_FACTS, swarmFacts),
                Map.entry(SupervisorGraphState.SWARM_STATE_VERSION, swarmStateVersion)
        );

        progress(progressEmitter, SupervisorProgressSupport.STAGE_GRAPH, 36, "그래프 입력 파라미터 설정 완료", Map.of(
                "sessionId", shortSessionId(request.sessionId()),
                "model", request.model() == null ? "openai" : request.model(),
                "historySize", history.size(),
                "startNode", SupervisorRuntimeState.HISTORY_LOADED.value()
        ));

        progress(progressEmitter, SupervisorProgressSupport.STAGE_GRAPH, 37, "→ PLAN 노드 실행 예정 (라우팅 계획 수립)", Map.of(
                "nodeType", "PLAN",
                "agent", "plannerAgent",
                "input", "userMessage + history"
        ));

        SupervisorGraphState state = graph.invoke(graphInput, configBuilder.build())
                .orElseGet(() -> new SupervisorGraphState(graphInput));
        SupervisorPlanningContext context = state.toPlanningContext();
        throwIfCanceled(taskId, canceled);

        // 3단계: 그래프 실행 완료 및 결과 확인 (38%)
        progress(progressEmitter, SupervisorProgressSupport.STAGE_GRAPH, 38, "✓ 그래프 실행 완료. 노드별 결과를 확인합니다...", Map.of(
                "finalNode", safe(context.getCurrentNode()),
                "planCount", context.getRoutingPlans().size(),
                "resultsCount", context.getResults().size()
        ));

        // PLAN 노드 결과 출력
        if (!context.getRoutingPlans().isEmpty()) {
            progress(progressEmitter, SupervisorProgressSupport.STAGE_GRAPH, 39, "✓ PLAN 노드 실행 완료", Map.of(
                    "nodeType", "PLAN",
                    "output", context.getRoutingPlans().size() + "개의 라우팅 계획 생성",
                    "agents", context.getRoutingPlans().stream().map(RoutingPlan::agentKey).toList().toString()
            ));
        }

        // INVOKE 노드 결과 출력 (그래프 내에서 실행된 경우)
        if (!context.getResults().isEmpty()) {
            progress(progressEmitter, SupervisorProgressSupport.STAGE_GRAPH, 39, "✓ INVOKE 노드 실행 완료 (그래프 내부)", Map.of(
                    "nodeType", "INVOKE",
                    "output", context.getResults().size() + "개의 하위 에이전트 호출 결과",
                    "results", context.getResults().stream().map(r -> r.agentKey() + ":" + r.status()).toList().toString()
            ));
        }

        long handoffPlanCount = context.getRoutingPlans().stream().filter(RoutingPlan::isHandoff).count();
        Map<String, Object> handoffProgressMetadata = handoffProgressMetadata(state, handoffPlanCount, context.getRoutingPlans().size());
        if (handoffPlanCount > 0) {
            progress(progressEmitter, SupervisorProgressSupport.STAGE_HANDOFF_APPLIED, 40, "handoff 계획이 반영되었습니다.", handoffProgressMetadata);
        } else {
            progress(progressEmitter, SupervisorProgressSupport.STAGE_HANDOFF_SKIPPED, 40, "handoff 적용 없이 기본 라우팅을 유지합니다.", handoffProgressMetadata);
        }

        progress(progressEmitter, SupervisorProgressSupport.STAGE_SWARM, 41, "Swarm 라우팅 반영 완료", Map.of(
                "swarmStateVersion", context.getSwarmStateVersion(),
                "finalPlanCount", context.getRoutingPlans().size()
        ));

        emitRoutingPlanDetails(request, context, progressEmitter);
        emitGraphInvocationSummary(request, context, progressEmitter);
        invokeFallbackIfRequired(request, taskId, canceled, context, progressEmitter);
        emitRoutingWarnings(request, context, progressEmitter);

        return context;
    }

    /**
     * 그래프가 계산한 라우팅 계획을 로그/진행 이벤트로 출력한다.
     *
     * @param request supervisor 요청
     * @param context planning context
     * @param progressEmitter 진행 이벤트 emitter
     */
    private void emitRoutingPlanDetails(
            SupervisorAgentRequest request,
            SupervisorPlanningContext context,
            ProgressEmitter progressEmitter
    ) {
        logger.info("Supervisor planning result sessionId={}, planCount={}, plans={}",
                request.sessionId(), context.getRoutingPlans().size(),
                context.getRoutingPlans().stream().map(plan -> plan.agentKey() + ":" + plan.method()).toList());

        progress(progressEmitter, SupervisorProgressSupport.STAGE_PLANNING, 40, "라우팅 계획이 수립되었습니다.", Map.of(
                "planCount", context.getRoutingPlans().size()
        ));

        int planIndex = 0;
        for (RoutingPlan plan : context.getRoutingPlans()) {
            logger.info(
                    "Supervisor selected downstream sessionId={}, agentKey={}, method={}, priority={}, reason={}, argumentKeys={}",
                    request.sessionId(),
                    plan.agentKey(),
                    plan.method(),
                    plan.priority(),
                    sanitize(plan.reason()),
                    plan.arguments() == null ? List.of() : plan.arguments().keySet()
            );

            progress(progressEmitter, SupervisorProgressSupport.STAGE_ROUTING, 50 + (planIndex * 2),
                    "📋 라우팅 계획 #" + (planIndex + 1) + ": " + plan.agentKey() + " → " + plan.method(),
                    Map.of(
                            "planIndex", planIndex + 1,
                            "agentKey", plan.agentKey(),
                            "method", plan.method(),
                            "priority", plan.priority(),
                            "reason", sanitize(plan.reason()),
                            "arguments", summarizeArguments(plan.arguments())
                    ));
            planIndex++;
        }
    }

    /**
     * 그래프 내부에서 이미 실행된 downstream 결과를 로그/진행 이벤트로 출력한다.
     *
     * @param request supervisor 요청
     * @param context planning context
     * @param progressEmitter 진행 이벤트 emitter
     */
    private void emitGraphInvocationSummary(
            SupervisorAgentRequest request,
            SupervisorPlanningContext context,
            ProgressEmitter progressEmitter
    ) {
        if (context.getResults().isEmpty()) {
            return;
        }

        logger.info("Supervisor graph downstream aggregation sessionId={}, resultsCount={}",
                request.sessionId(), context.getResults().size());
        for (DownstreamCallResult result : context.getResults()) {
            logger.info("Supervisor graph downstream result sessionId={}, {}",
                    request.sessionId(), summarizeResult(result));
        }
        progress(progressEmitter, SupervisorProgressSupport.STAGE_INVOKING, 60, "그래프 내에서 하위 에이전트가 이미 실행되었습니다. 결과를 확인합니다.", Map.of(
                "resultsCount", context.getResults().size(),
                "executedInGraph", true
        ));

        int resultIndex = 0;
        for (DownstreamCallResult result : context.getResults()) {
            progress(progressEmitter, SupervisorProgressSupport.STAGE_INVOKING, 60 + (resultIndex * 3), "✓ " + result.agentKey() + " 실행 완료", Map.of(
                    "agentKey", result.agentKey(),
                    "status", result.status(),
                    "errorCode", safe(result.errorCode()),
                    "payloadLength", result.payload() == null ? 0 : result.payload().length()
            ));
            resultIndex++;
        }

        progress(progressEmitter, SupervisorProgressSupport.STAGE_INVOKING, 75, "모든 하위 에이전트 실행 완료 (그래프 내에서 처리됨)", Map.of(
                "resultsCount", context.getResults().size()
        ));
    }

    /**
     * 그래프 결과가 비어 있으면 기존 fallback 규칙으로 downstream 호출을 수행한다.
     *
     * @param request supervisor 요청
     * @param taskId task 식별자
     * @param canceled 취소 플래그
     * @param context planning context
     * @param progressEmitter 진행 이벤트 emitter
     */
    private void invokeFallbackIfRequired(
            SupervisorAgentRequest request,
            String taskId,
            AtomicBoolean canceled,
            SupervisorPlanningContext context,
            ProgressEmitter progressEmitter
    ) {
        if (!context.getResults().isEmpty() || context.getRoutingPlans().isEmpty()) {
            return;
        }

        int maxIterations = Math.min(5, context.getRoutingPlans().size());
        progress(progressEmitter, SupervisorProgressSupport.STAGE_INVOKING, 60, "→ INVOKE 노드 수동 실행: 하위 에이전트 호출 시작", Map.of(
                "nodeType", "INVOKE",
                "executionMode", "manual(fallback)",
                "totalCalls", maxIterations
        ));

        for (int i = 0; i < maxIterations; i++) {
            throwIfCanceled(taskId, canceled);
            RoutingPlan plan = context.getRoutingPlans().get(i);

            int currentProgress = 60 + (i * 15 / maxIterations);
            progress(progressEmitter, SupervisorProgressSupport.STAGE_INVOKING, currentProgress,
                    "🔄 하위 에이전트 호출 #" + (i + 1) + "/" + maxIterations + ": " + plan.agentKey(),
                    Map.of(
                            "callIndex", i + 1,
                            "totalCalls", maxIterations,
                            "agentKey", plan.agentKey(),
                            "method", plan.method(),
                            "endpoint", "/a2a/" + plan.agentKey() + "/" + plan.method(),
                            "arguments", summarizeArguments(plan.arguments())
                    ));

            DownstreamCallResult result = invocationService.invoke(plan, context);
            context.addResult(result);
            swarmCoordinator.recordInvocationBatch(taskId, request.sessionId(), List.of(result));

            logger.info("Supervisor downstream result sessionId={}, agentKey={}, status={}, errorCode={}",
                    request.sessionId(), result.agentKey(), result.status(), result.errorCode());

            progress(progressEmitter, SupervisorProgressSupport.STAGE_INVOKING, currentProgress + 3,
                    "✓ 호출 완료 #" + (i + 1) + ": " + result.agentKey() + " → " + result.status(),
                    Map.of(
                            "callIndex", i + 1,
                            "agentKey", result.agentKey(),
                            "status", result.status(),
                            "errorCode", safe(result.errorCode()),
                            "payloadLength", result.payload() == null ? 0 : result.payload().length(),
                            "hasError", result.errorCode() != null && !result.errorCode().isBlank()
                    ));
        }

        progress(progressEmitter, SupervisorProgressSupport.STAGE_INVOKING, 75, "✓ INVOKE 노드 완료 (수동 실행)", Map.of(
                "nodeType", "INVOKE",
                "executionMode", "manual(fallback)",
                "resultsCount", context.getResults().size()
        ));
    }

    /**
     * 라우팅/결과 유무에 따라 경고 로그 및 사용자 진행 메시지를 출력한다.
     *
     * @param request supervisor 요청
     * @param context planning context
     * @param progressEmitter 진행 이벤트 emitter
     */
    private void emitRoutingWarnings(
            SupervisorAgentRequest request,
            SupervisorPlanningContext context,
            ProgressEmitter progressEmitter
    ) {
        if (context.getRoutingPlans().isEmpty()) {
            logger.warn("Supervisor planned no downstream calls sessionId={}, message={}",
                    request.sessionId(), request.message());
            progress(progressEmitter, SupervisorProgressSupport.STAGE_ROUTING, 75, "라우팅 계획: 직접 응답(하위 에이전트 호출 없음)");
            return;
        }
        if (context.getResults().isEmpty()) {
            logger.warn("Supervisor routing exists but downstream results are empty sessionId={}, planCount={}",
                    request.sessionId(), context.getRoutingPlans().size());
        }
    }

    private Map<String, Object> handoffProgressMetadata(
            SupervisorGraphState state,
            long handoffPlanCount,
            int totalPlanCount
    ) {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("handoffEnabled", state.value(SupervisorGraphState.HANDOFF_ENABLED).map(this::toBoolean).orElse(false));
        metadata.put("handoffPlanCount", handoffPlanCount);
        metadata.put("totalPlanCount", totalPlanCount);

        Map<String, Object> representative = representativeHandoffValidation(state);
        metadata.put("fromAgent", readString(representative, "fromAgentKey"));
        metadata.put("toAgent", readString(representative, "nextAgentKey"));
        metadata.put("reason", firstNonBlank(readString(representative, "reason"), readString(representative, "reasonCode")));
        metadata.put("hopCount", readInt(representative, "hopCount"));
        return Map.copyOf(metadata);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> representativeHandoffValidation(SupervisorGraphState state) {
        Object raw = state.value(SupervisorGraphState.HANDOFF_VALIDATIONS).orElse(List.of());
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return Map.of();
        }

        Map<String, Object> first = Map.of();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> source)) {
                continue;
            }
            LinkedHashMap<String, Object> mapped = new LinkedHashMap<>();
            source.forEach((k, v) -> mapped.put(String.valueOf(k), v));
            if (first.isEmpty()) {
                first = mapped;
            }
            if (toBoolean(mapped.get("accepted"))) {
                return mapped;
            }
        }
        return first;
    }

    private boolean toBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return "true".equalsIgnoreCase(String.valueOf(value));
    }

    private String readString(Map<String, Object> map, String key) {
        if (map == null || map.isEmpty()) {
            return "";
        }
        Object value = map.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private int readInt(Map<String, Object> map, String key) {
        if (map == null || map.isEmpty()) {
            return 0;
        }
        Object value = map.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private boolean isCanceled(String taskId, AtomicBoolean canceled) {
        if (canceled.get()) {
            return true;
        }
        return lifecycleService.get(taskId)
                .map(snapshot -> snapshot.status() == A2aTaskStatus.CANCELED)
                .orElse(false);
    }

    private void throwIfCanceled(String taskId, AtomicBoolean canceled) {
        if (isCanceled(taskId, canceled)) {
            throw new CancellationException("Supervisor task canceled");
        }
    }

    /**
     * 응답 종료 시 히스토리와 체크포인트를 저장한다.
     *
     * @param context 실행 컨텍스트
     * @param assistantResponse 최종 assistant 응답 문자열
     */
    private void persist(SupervisorPlanningContext context, String assistantResponse) {
        List<String> updated = new ArrayList<>(context.getHistory());
        updated.add("user: " + context.getUserMessage());
        updated.add("assistant: " + assistantResponse);
        conversationStore.save(context.getSessionId(), updated);
        checkpointStore.saveCheckpoint(context.getSessionId(), "state=COMPLETED;at=" + Instant.now());
    }

    /**
     * 예외 메시지를 외부 응답용으로 길이 제한/정규화한다.
     *
     * @param message 원본 예외 메시지
     * @return sanitize된 메시지
     */
    private String sanitize(String message) {
        if (message == null || message.isBlank()) {
            return "Unexpected supervisor error";
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }

    /**
     * 체크포인트 payload 무결성을 검증하고 유효값만 반환한다.
     * <p>
     * 형식/상태/시간 값이 잘못된 경우 해당 세션 체크포인트를 정리하고 빈 문자열을 반환한다.
     *
     * @param sessionId 세션 id
     * @return 유효한 checkpoint payload 또는 빈 문자열
     */
    private String resolveCheckpointId(String sessionId) {
        String payload = checkpointStore.loadCheckpoint(sessionId).orElse("");
        if (payload.isBlank()) {
            return "";
        }
        Matcher matcher = CHECKPOINT_PATTERN.matcher(payload);
        if (!matcher.matches()) {
            checkpointStore.clear(sessionId);
            return "";
        }
        String state = matcher.group(1);
        String at = matcher.group(2);
        if (!ALLOWED_CHECKPOINT_STATES.contains(state)) {
            checkpointStore.clear(sessionId);
            return "";
        }
        try {
            Instant.parse(at);
            return payload;
        } catch (Exception ignored) {
            checkpointStore.clear(sessionId);
            return "";
        }
    }
}
