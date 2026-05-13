package com.example.springsupervisorai.service;

import com.example.springsupervisorai.a2a.lifecycle.SupervisorA2aLifecycleService;
import com.example.springsupervisorai.a2a.task.A2aTaskStatus;
import com.example.springsupervisorai.model.DownstreamCallResult;
import com.example.springsupervisorai.model.RoutingPlan;
import com.example.springsupervisorai.model.SupervisorAgentRequest;
import com.example.springsupervisorai.model.SupervisorErrorCode;
import com.example.springsupervisorai.model.SupervisorGraphState;
import com.example.springsupervisorai.model.SupervisorGraphSnapshot;
import com.example.springsupervisorai.model.SupervisorOutputEvent;
import com.example.springsupervisorai.model.SupervisorOutputEventType;
import com.example.springsupervisorai.model.SupervisorPlanningContext;
import com.example.springsupervisorai.service.agent.compose.SupervisorResponseComposeService;
import com.example.springsupervisorai.service.agent.invoke.A2AInvocationService;
import com.example.springsupervisorai.service.agent.store.ConversationStore;
import com.example.springsupervisorai.service.agent.store.GraphCheckpointStore;
import com.example.springsupervisorai.service.agent.swarm.SupervisorSwarmCoordinator;
import com.example.springsupervisorai.service.agent.graph.SupervisorGraphStateMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.CancellationException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

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

    private final SupervisorResponseComposeService composeService;
    private final SupervisorA2aLifecycleService lifecycleService;
    private final SupervisorProgressPublisher progressPublisher;
    private final SupervisorExecutionPersistenceService persistenceService;
    private final SupervisorFallbackInvokeService fallbackInvokeService;
    private final SupervisorGraphExecutionService graphExecutionService;
    private final SupervisorExceptionTranslator exceptionTranslator;
    private final SupervisorExecutionSummaryEmitter executionSummaryEmitter;

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
            SupervisorResponseComposeService composeService,
            SupervisorA2aLifecycleService lifecycleService,
            A2AInvocationService invocationService,
            SupervisorProgressPublisher progressPublisher,
            SupervisorExecutionPersistenceService persistenceService,
            SupervisorFallbackInvokeService fallbackInvokeService,
            SupervisorGraphExecutionService graphExecutionService,
            SupervisorExceptionTranslator exceptionTranslator,
            SupervisorExecutionSummaryEmitter executionSummaryEmitter
    ) {
        this.composeService = composeService;
        this.lifecycleService = lifecycleService;
        this.progressPublisher = progressPublisher;
        this.persistenceService = persistenceService;
        this.fallbackInvokeService = fallbackInvokeService;
        this.graphExecutionService = graphExecutionService;
        this.exceptionTranslator = exceptionTranslator;
        this.executionSummaryEmitter = executionSummaryEmitter;
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
        return executeEvents(request, taskId).map(SupervisorOutputEventSupport::serialize);
    }

    /**
     * supervisor 요청을 실행하고 구조화된 출력 이벤트 스트림을 반환한다.
     */
    public Flux<SupervisorOutputEvent> executeEvents(SupervisorAgentRequest request, String taskId) {
        AtomicBoolean canceled = new AtomicBoolean(false);
        Sinks.Many<SupervisorOutputEvent> progressSink = Sinks.many().multicast().onBackpressureBuffer();

        logger.info("Supervisor execute start taskId={}, sessionId={}, model={}", taskId, request.sessionId(), request.model());

        emitInitialProgress(progressSink, request);

        Flux<SupervisorOutputEvent> sharedProgress = progressSink.asFlux().share();
        Mono<SupervisorPlanningContext> planningMono = createPlanningContextMono(request, taskId, canceled, progressSink);
        Flux<SupervisorOutputEvent> planningProgress = sharedProgress.takeUntilOther(planningMono);

        return Flux.concat(
                planningProgress,
                planningMono
                        .flatMapMany(context -> composeResponse(request, taskId, canceled, context))
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
    private void emitInitialProgress(Sinks.Many<SupervisorOutputEvent> progressSink, SupervisorAgentRequest request) {
        progressPublisher.emitEvent(
                progressSink,
                "",
                request.sessionId(),
                "INITIALIZING",
                SupervisorProgressSupport.STAGE_INITIALIZING,
                0,
                "요청을 접수했습니다.",
                Map.of(
                        "sessionId", shortSessionId(request.sessionId()),
                        "model", safe(request.model())
                )
        );
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
            Sinks.Many<SupervisorOutputEvent> progressSink
    ) {
        return Mono.fromCallable(
                        () -> invokeGraph(
                                request,
                                taskId,
                                canceled,
                                (stage, progress, message, metadata) -> progressPublisher.emitEvent(
                                        progressSink,
                                        taskId,
                                        request.sessionId(),
                                        resolveNodeType(stage, metadata),
                                        stage,
                                        progress,
                                        message,
                                        metadata
                                )
                        )
                )
                .subscribeOn(Schedulers.boundedElastic())
                .doOnError(error -> progressPublisher.emitEvent(
                        progressSink,
                        taskId,
                        request.sessionId(),
                        "ERROR",
                        SupervisorProgressSupport.STAGE_ERROR,
                        0,
                        "실행 중 오류가 발생했습니다.",
                        Map.of("error", exceptionTranslator.sanitize(error.getMessage()))
                ))
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
    private Flux<SupervisorOutputEvent> composeResponse(
            SupervisorAgentRequest request,
            String taskId,
            AtomicBoolean canceled,
            SupervisorPlanningContext context
    ) {
        if (isCanceled(taskId, canceled)) {
            return Flux.empty();
        }

        StringBuilder answer = new StringBuilder();
        StringBuilder a2uiProtocol = new StringBuilder();
        AtomicBoolean failed = new AtomicBoolean(false);
        SupervisorOutputEvent composingProgress = progressEvent(
                taskId,
                request.sessionId(),
                "COMPOSE",
                SupervisorProgressSupport.STAGE_COMPOSING,
                80,
                "하위 에이전트 실행 결과를 정리하고 답변을 생성합니다...",
                Map.of("resultsCount", context.getResults().size())
        );
        SupervisorOutputEvent completedProgress = progressEvent(
                taskId,
                request.sessionId(),
                "COMPOSE",
                SupervisorProgressSupport.STAGE_COMPLETED,
                100,
                "응답 생성이 완료되었습니다.",
                Map.of("answerLength", answer.length())
        );

        return Flux.concat(
                Flux.just(composingProgress),
                composeService.streamComposeEvents(context)
                        .onErrorResume(error -> handleComposeError(taskId, request.sessionId(), canceled, failed, error))
                        .doOnNext(event -> {
                            if (event.type() == SupervisorOutputEventType.TEXT) {
                                answer.append(event.content());
                            } else if (event.type() == SupervisorOutputEventType.A2UI) {
                                a2uiProtocol.append(event.content());
                            }
                        })
                        .doOnComplete(() -> onComposeCompleted(request, taskId, context, answer, a2uiProtocol, failed))
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
    private Flux<SupervisorOutputEvent> handleComposeError(
            String taskId,
            String sessionId,
            AtomicBoolean canceled,
            AtomicBoolean failed,
            Throwable error
    ) {
        if (error instanceof CancellationException || isCanceled(taskId, canceled)) {
            return Flux.empty();
        }
        failed.set(true);
        SupervisorExceptionTranslator.Failure failure = exceptionTranslator.composeFailure(error);
        persistenceService.markFailed(taskId, failure.errorCode().value(), failure.detail());
        return Flux.concat(
                Flux.just(progressEvent(
                        taskId,
                        sessionId,
                        "COMPOSE",
                        SupervisorProgressSupport.STAGE_ERROR,
                        0,
                        failure.userMessage(),
                        Map.of("error", failure.detail())
                )),
                Flux.just(SupervisorOutputEvent.error(failure.userMessage()))
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
            StringBuilder a2uiProtocol,
            AtomicBoolean failed
    ) {
        String a2uiPayload = a2uiProtocol.toString();
        if (a2uiPayload.isBlank()) {
            persistenceService.persistCompletion(context, answer.toString());
        } else {
            persistenceService.persistA2uiCompletion(context, answer.toString(), a2uiPayload);
        }
        progressPublisher.recordNodeEvent(taskId, request.sessionId(), "COMPOSE", "Compose completed", Map.of(
                "answerLength", answer.length(),
                "a2uiProtocolLength", a2uiPayload.length(),
                "resultsCount", context.getResults().size()
        ));
        logger.info("Supervisor execute finished taskId={}, sessionId={}, results={}, responseLength={}, a2uiProtocolLength={}",
                taskId, request.sessionId(), context.getResults().size(), answer.length(), a2uiPayload.length());
        if (!failed.get()) {
            persistenceService.markCompleted(taskId, answer.toString());
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
    private Flux<SupervisorOutputEvent> handleOrchestrationError(
            SupervisorAgentRequest request,
            String taskId,
            AtomicBoolean canceled,
            Sinks.Many<SupervisorOutputEvent> progressSink,
            Throwable error
    ) {
        if (error instanceof CancellationException || isCanceled(taskId, canceled)) {
            logger.info("Supervisor execute canceled during orchestration taskId={}, sessionId={}", taskId, request.sessionId());
            progressSink.tryEmitComplete();
            return Flux.empty();
        }
        SupervisorExceptionTranslator.Failure failure = exceptionTranslator.orchestrationFailure(error);
        logger.error("Supervisor execute failed taskId={}, sessionId={}, error={}",
                taskId, request.sessionId(), failure.detail());
        persistenceService.markFailed(taskId, failure.errorCode().value(), failure.detail());
        progressSink.tryEmitComplete();
        return Flux.concat(
                Flux.just(progressEvent(
                        taskId,
                        request.sessionId(),
                        "ORCHESTRATOR",
                        SupervisorProgressSupport.STAGE_ERROR,
                        0,
                        failure.userMessage(),
                        Map.of("error", failure.detail())
                )),
                Flux.just(SupervisorOutputEvent.error(failure.userMessage()))
        );
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
        persistenceService.clearSession(sessionId);
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

    private SupervisorOutputEvent progressEvent(
            String taskId,
            String sessionId,
            String nodeType,
            String stage,
            int progress,
            String message,
            Map<String, Object> metadata
    ) {
        progressPublisher.recordProgress(taskId, sessionId, nodeType, stage, progress, message, metadata);
        return SupervisorOutputEvent.progress(SupervisorProgressSupport.event(stage, progress, message, metadata));
    }

    private String resolveNodeType(String stage, Map<String, Object> metadata) {
        if (metadata != null) {
            Object nodeType = metadata.get("nodeType");
            if (nodeType != null && !String.valueOf(nodeType).isBlank()) {
                return String.valueOf(nodeType);
            }
        }
        return switch (stage) {
            case SupervisorProgressSupport.STAGE_PLANNING -> "PLAN";
            case SupervisorProgressSupport.STAGE_ROUTING -> "ROUTING";
            case SupervisorProgressSupport.STAGE_INVOKING -> "INVOKE";
            case SupervisorProgressSupport.STAGE_HANDOFF,
                 SupervisorProgressSupport.STAGE_HANDOFF_APPLIED,
                 SupervisorProgressSupport.STAGE_HANDOFF_SKIPPED -> "HANDOFF";
            case SupervisorProgressSupport.STAGE_SWARM -> "SWARM";
            case SupervisorProgressSupport.STAGE_GRAPH -> "GRAPH";
            case SupervisorProgressSupport.STAGE_COMPOSING,
                 SupervisorProgressSupport.STAGE_COMPLETED -> "COMPOSE";
            case SupervisorProgressSupport.STAGE_ANALYZING -> "ANALYZE";
            case SupervisorProgressSupport.STAGE_INITIALIZING -> "INITIALIZING";
            case SupervisorProgressSupport.STAGE_ERROR -> "ERROR";
            default -> stage == null ? "" : stage.toUpperCase();
        };
    }

    private SupervisorPlanningContext invokeGraph(
            SupervisorAgentRequest request,
            String taskId,
            AtomicBoolean canceled,
            ProgressEmitter progressEmitter
    ) {
        SupervisorGraphExecutionService.GraphExecutionResult executionResult = graphExecutionService.execute(
                request,
                taskId,
                canceled,
                progressEmitter::emit,
                () -> isCanceled(taskId, canceled)
        );
        SupervisorGraphState state = executionResult.state();
        SupervisorPlanningContext context = executionResult.context();
        SupervisorGraphSnapshot snapshot = SupervisorGraphStateMapper.INSTANCE.snapshot(state);
        executionSummaryEmitter.emitGraphCompletion(context, snapshot, progressEmitter::emit);
        executionSummaryEmitter.emitRoutingPlanDetails(request, context, exceptionTranslator::sanitize, progressEmitter::emit);
        executionSummaryEmitter.emitGraphInvocationSummary(request, context, progressEmitter::emit);
        fallbackInvokeService.invokeIfRequired(
                request,
                taskId,
                canceled,
                context,
                progressEmitter::emit,
                () -> isCanceled(taskId, canceled)
        );
        executionSummaryEmitter.emitRoutingWarnings(request, context, progressEmitter::emit);

        return context;
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

}
