package com.example.springsupervisorai.service;

import com.example.springsupervisorai.a2a.lifecycle.SupervisorA2aLifecycleService;
import com.example.springsupervisorai.a2a.task.A2aTaskStatus;
import com.example.springsupervisorai.model.DownstreamCallResult;
import com.example.springsupervisorai.model.RoutingPlan;
import com.example.springsupervisorai.model.SupervisorAgentRequest;
import com.example.springsupervisorai.model.SupervisorErrorCode;
import com.example.springsupervisorai.model.SupervisorGraphState;
import com.example.springsupervisorai.model.SupervisorPlanningContext;
import com.example.springsupervisorai.model.SupervisorProgressEvent;
import com.example.springsupervisorai.model.SupervisorRuntimeState;
import com.example.springsupervisorai.service.agent.compose.SupervisorResponseComposeService;
import com.example.springsupervisorai.service.agent.graph.SupervisorStateGraphFactory;
import com.example.springsupervisorai.service.agent.invoke.A2AInvocationService;
import com.example.springsupervisorai.service.agent.store.ConversationStore;
import com.example.springsupervisorai.service.agent.store.GraphCheckpointStore;
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
import java.util.concurrent.CancellationException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
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

    /**
     * 오케스트레이터 의존성을 생성자 주입으로 초기화한다.
     *
     * @param conversationStore 세션 히스토리 저장소
     * @param checkpointStore 그래프 체크포인트 저장소
     * @param graphFactory supervisor 상태 그래프 팩토리
     * @param composeService 최종 응답 합성 서비스
     * @param lifecycleService supervisor A2A task 라이프사이클 서비스
     * @param invocationService downstream invocation 포트
     */
    public SupervisorAgentOrchestrator(
            ConversationStore conversationStore,
            GraphCheckpointStore checkpointStore,
            SupervisorStateGraphFactory graphFactory,
            SupervisorResponseComposeService composeService,
            SupervisorA2aLifecycleService lifecycleService,
            A2AInvocationService invocationService
    ) {
        this.conversationStore = conversationStore;
        this.checkpointStore = checkpointStore;
        this.graphFactory = graphFactory;
        this.composeService = composeService;
        this.lifecycleService = lifecycleService;
        this.invocationService = invocationService;
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

        // 단계별 진행 상황 emit
        emitProgress(progressSink, "initializing", 0, "요청을 접수했습니다.", Map.of(
                "sessionId", shortSessionId(request.sessionId()),
                "model", safe(request.model())
        ));

        // progress flux를 공유 가능하게 만듦
        Flux<String> sharedProgress = progressSink.asFlux().share();

        Mono<SupervisorPlanningContext> planningMono = Mono.fromCallable(
                        () -> invokeGraph(request, taskId, canceled, (stage, progress, message, metadata) ->
                                emitProgress(progressSink, stage, progress, message, metadata))
                )
                .subscribeOn(Schedulers.boundedElastic())
                .doOnError(error -> emitProgress(progressSink, "error", 0, "실행 중 오류가 발생했습니다.", Map.of(
                        "error", sanitize(error.getMessage())
                )))
                .cache();

        Flux<String> planningProgress = sharedProgress
                .takeUntilOther(planningMono);

        return Flux.concat(
                planningProgress,
                planningMono
                        .flatMapMany(context -> {
                            if (isCanceled(taskId, canceled)) {
                                progressSink.tryEmitComplete();
                                return Flux.empty();
                            }

                            StringBuilder answer = new StringBuilder();
                            AtomicBoolean failed = new AtomicBoolean(false);

                            // composing 시작 진행 상황 (80%)
                            String composingProgress = formatProgress("composing", 80, "하위 에이전트 실행 결과를 정리하고 답변을 생성합니다...", Map.of(
                                    "resultsCount", context.getResults().size()
                            ));

                            return Flux.concat(
                                    Flux.just(composingProgress),  // composing 80% 먼저 전송
                                    composeService.streamCompose(context)
                                            .onErrorResume(error -> {
                                                if (error instanceof CancellationException || isCanceled(taskId, canceled)) {
                                                    return Flux.empty();
                                                }
                                                failed.set(true);
                                                lifecycleService.markFailed(taskId, SupervisorErrorCode.COMPOSE_ERROR.value(), sanitize(error.getMessage()));
                                                return Flux.concat(
                                                        Flux.just(formatProgress("error", 0, "응답 합성 중 오류가 발생했습니다.", Map.of(
                                                                "error", sanitize(error.getMessage())
                                                        ))),
                                                        Flux.just("응답 합성 중 오류가 발생했습니다.")
                                                );
                                            })
                                            .doOnNext(answer::append)
                                            .doOnComplete(() -> {
                                                // 답변 스트림 완료 후 처리
                                                persist(context, answer.toString());
                                                logger.info("Supervisor execute finished taskId={}, sessionId={}, results={}, responseLength={}",
                                                        taskId, request.sessionId(), context.getResults().size(), answer.length());
                                                if (!failed.get()) {
                                                    lifecycleService.markCompleted(taskId, answer.toString());
                                                }
                                            })
                                            .concatWith(Flux.just("\n" + formatProgress("completed", 100, "응답 생성이 완료되었습니다.", Map.of(
                                                    "answerLength", answer.length()
                                            ))))
                            );
                        })
                        .onErrorResume(error -> {
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
                                    Flux.just(formatProgress("error", 0, "Supervisor 처리 중 오류가 발생했습니다.", Map.of(
                                            "error", sanitize(error.getMessage())
                                    ))),
                                    Flux.just("Supervisor 처리 중 오류가 발생했습니다.")
                            );
                        })
        ).doOnCancel(() -> {
            canceled.set(true);
            progressSink.tryEmitComplete();
        }).doFinally(signal -> progressSink.tryEmitComplete());
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
        SupervisorProgressEvent event = SupervisorProgressEvent.of(stage, progress, message, metadata);
        sink.tryEmitNext(formatProgress(event));
    }

    /**
     * 진행 상황 이벤트를 문자열 포맷으로 변환한다.
     *
     * @param event 진행 상황 이벤트
     * @return 포맷된 문자열
     */
    private String formatProgress(SupervisorProgressEvent event) {
        return formatProgress(event.stage(), event.progress(), event.message(), event.metadata());
    }

    /**
     * 진행 상황을 문자열 포맷으로 변환한다.
     *
     * @param stage 진행 단계
     * @param progress 진행률 (0-100)
     * @param message 사용자 메시지
     * @param metadata 추가 메타데이터
     * @return 포맷된 문자열
     */
    private String formatProgress(String stage, int progress, String message, Map<String, Object> metadata) {
        StringBuilder sb = new StringBuilder();
        sb.append("[supervisor]");
        sb.append(" [").append(stage).append("]");
        sb.append(" [").append(progress).append("%]");
        sb.append(" ").append(message);

        if (metadata != null && !metadata.isEmpty()) {
            sb.append(" {");
            metadata.forEach((key, value) -> sb.append(key).append("=").append(value).append(", "));
            sb.setLength(sb.length() - 2); // 마지막 ", " 제거
            sb.append("}");
        }

        sb.append("\n");
        return sb.toString();
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

    private SupervisorPlanningContext invokeGraph(
            SupervisorAgentRequest request,
            String taskId,
            AtomicBoolean canceled,
            ProgressEmitter progressEmitter
    ) {
        throwIfCanceled(taskId, canceled);

        // 1단계: 히스토리 로드 (10%)
        progressEmitter.emit("analyzing", 10, "질문 의도 분석을 시작합니다.", Map.of());
        List<String> history = conversationStore.load(request.sessionId());
        throwIfCanceled(taskId, canceled);

        progressEmitter.emit("analyzing", 20, "히스토리 로드 완료", Map.of(
                "historyCount", history.size()
        ));

        // 2단계: 체크포인트 복원 (30%)
        String checkpointId = resolveCheckpointId(request.sessionId());
        progressEmitter.emit("planning", 30, "라우팅 계획을 수립하고 있습니다...", Map.of(
                "hasCheckpoint", !checkpointId.isBlank()
        ));

        CompiledGraph<SupervisorGraphState> graph = graphFactory.getCompiledGraph();

        RunnableConfig.Builder configBuilder = RunnableConfig.builder().threadId(request.sessionId());
        if (!checkpointId.isBlank()) {
            configBuilder.checkPointId(checkpointId);
        }

        // 2.5단계: 그래프 실행 시작 (35%)
        progressEmitter.emit("planning", 35, "Supervisor 그래프를 실행합니다. (Planning → Invoking → Merging)", Map.of(
                "graphNodes", "PLAN, INVOKE, MERGE"
        ));

        // 그래프 입력 파라미터 출력
        Map<String, Object> graphInput = Map.of(
                SupervisorGraphState.SESSION_ID, request.sessionId(),
                SupervisorGraphState.USER_MESSAGE, request.message(),
                SupervisorGraphState.MODEL, request.model() == null ? "openai" : request.model(),
                SupervisorGraphState.HISTORY, history,
                SupervisorGraphState.CHECKPOINT_ID, checkpointId,
                SupervisorGraphState.CURRENT_NODE, SupervisorRuntimeState.HISTORY_LOADED.value(),
                SupervisorGraphState.ROUTING_INDEX, 0,
                SupervisorGraphState.ROUTING_PLANS, List.of(),
                SupervisorGraphState.DOWNSTREAM_RESULTS, List.of()
        );

        progressEmitter.emit("graph", 36, "그래프 입력 파라미터 설정 완료", Map.of(
                "sessionId", shortSessionId(request.sessionId()),
                "model", request.model() == null ? "openai" : request.model(),
                "historySize", history.size(),
                "startNode", SupervisorRuntimeState.HISTORY_LOADED.value()
        ));

        progressEmitter.emit("graph", 37, "→ PLAN 노드 실행 예정 (라우팅 계획 수립)", Map.of(
                "nodeType", "PLAN",
                "agent", "plannerAgent",
                "input", "userMessage + history"
        ));

        SupervisorGraphState state = graph.invoke(graphInput, configBuilder.build())
                .orElseGet(() -> new SupervisorGraphState(graphInput));
        SupervisorPlanningContext context = state.toPlanningContext();
        throwIfCanceled(taskId, canceled);

        // 3단계: 그래프 실행 완료 및 결과 확인 (38%)
        progressEmitter.emit("graph", 38, "✓ 그래프 실행 완료. 노드별 결과를 확인합니다...", Map.of(
                "finalNode", safe(context.getCurrentNode()),
                "planCount", context.getRoutingPlans().size(),
                "resultsCount", context.getResults().size()
        ));

        // PLAN 노드 결과 출력
        if (!context.getRoutingPlans().isEmpty()) {
            progressEmitter.emit("graph", 39, "✓ PLAN 노드 실행 완료", Map.of(
                    "nodeType", "PLAN",
                    "output", context.getRoutingPlans().size() + "개의 라우팅 계획 생성",
                    "agents", context.getRoutingPlans().stream().map(RoutingPlan::agentKey).toList().toString()
            ));
        }

        // INVOKE 노드 결과 출력 (그래프 내에서 실행된 경우)
        if (!context.getResults().isEmpty()) {
            progressEmitter.emit("graph", 39, "✓ INVOKE 노드 실행 완료 (그래프 내부)", Map.of(
                    "nodeType", "INVOKE",
                    "output", context.getResults().size() + "개의 하위 에이전트 호출 결과",
                    "results", context.getResults().stream().map(r -> r.agentKey() + ":" + r.status()).toList().toString()
            ));
        }

        // 3.5단계: 라우팅 계획 완료 (40%)
        logger.info("Supervisor planning result sessionId={}, planCount={}, plans={}",
                request.sessionId(), context.getRoutingPlans().size(),
                context.getRoutingPlans().stream().map(plan -> plan.agentKey() + ":" + plan.method()).toList());

        progressEmitter.emit("planning", 40, "라우팅 계획이 수립되었습니다.", Map.of(
                "planCount", context.getRoutingPlans().size()
        ));

        // 4단계: 라우팅 계획 상세 출력 (50%)
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

            progressEmitter.emit("routing", 50 + (planIndex * 2),
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

        // 5단계: 이미 실행된 결과가 있는 경우 출력 (60%)
        if (!context.getResults().isEmpty()) {
            progressEmitter.emit("invoking", 60, "그래프 내에서 하위 에이전트가 이미 실행되었습니다. 결과를 확인합니다.", Map.of(
                    "resultsCount", context.getResults().size(),
                    "executedInGraph", true
            ));

            int resultIndex = 0;
            for (DownstreamCallResult result : context.getResults()) {
                progressEmitter.emit("invoking", 60 + (resultIndex * 3), "✓ " + result.agentKey() + " 실행 완료", Map.of(
                        "agentKey", result.agentKey(),
                        "status", result.status(),
                        "errorCode", safe(result.errorCode()),
                        "payloadLength", result.payload() == null ? 0 : result.payload().length()
                ));
                resultIndex++;
            }

            progressEmitter.emit("invoking", 75, "모든 하위 에이전트 실행 완료 (그래프 내에서 처리됨)", Map.of(
                    "resultsCount", context.getResults().size()
            ));
        }

        // 6단계: 하위 에이전트 호출 (60-75%)
        if (context.getResults().isEmpty() && !context.getRoutingPlans().isEmpty()) {
            int maxIterations = Math.min(5, context.getRoutingPlans().size());
            progressEmitter.emit("invoking", 60, "→ INVOKE 노드 수동 실행: 하위 에이전트 호출 시작", Map.of(
                    "nodeType", "INVOKE",
                    "executionMode", "manual(fallback)",
                    "totalCalls", maxIterations
            ));

            for (int i = 0; i < maxIterations; i++) {
                throwIfCanceled(taskId, canceled);
                RoutingPlan plan = context.getRoutingPlans().get(i);

                int currentProgress = 60 + (i * 15 / maxIterations);
                progressEmitter.emit("invoking", currentProgress,
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

                logger.info("Supervisor downstream result sessionId={}, agentKey={}, status={}, errorCode={}",
                        request.sessionId(), result.agentKey(), result.status(), result.errorCode());

                progressEmitter.emit("invoking", currentProgress + 3,
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

            progressEmitter.emit("invoking", 75, "✓ INVOKE 노드 완료 (수동 실행)", Map.of(
                    "nodeType", "INVOKE",
                    "executionMode", "manual(fallback)",
                    "resultsCount", context.getResults().size()
            ));
        }

        // 7단계: 직접 응답 케이스 (75%)
        if (context.getRoutingPlans().isEmpty()) {
            logger.warn("Supervisor planned no downstream calls sessionId={}, message={}",
                    request.sessionId(), request.message());
            progressEmitter.emit("routing", 75, "라우팅 계획: 직접 응답(하위 에이전트 호출 없음)", Map.of());
        }

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
