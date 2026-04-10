package com.example.springai.service.agent.orchestrator;

import com.example.springai.a2a.context.A2aExecutionContext;
import com.example.springai.a2a.lifecycle.A2aLifecycleService;
import com.example.springai.model.agent.AgentChatRequest;
import com.example.springai.model.agent.AgentGraphState;
import com.example.springai.model.agent.AgentScope;
import com.example.springai.model.agent.PlanningContext;
import com.example.springai.a2a.task.A2aTaskStatus;
import com.example.springai.service.agent.compose.ResponseComposeService;
import com.example.springai.service.agent.graph.AgentStateGraphFactory;
import com.example.springai.service.agent.security.HumanMessageService;
import com.example.springai.service.agent.store.ConversationStore;
import com.example.springai.service.agent.store.GraphCheckpointStore;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.RunnableConfig;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 에이전트 실행 전체 흐름(그래프 실행, 응답 생성, 상태 저장)을 조율하는 오케스트레이터.
 */
@Component
public class AgentOrchestrator {

    private final ConversationStore conversationStore;
    private final GraphCheckpointStore checkpointStore;
    private final AgentStateGraphFactory graphFactory;
    private final ResponseComposeService responseComposeService;
    private final HumanMessageService humanMessageService;
    private final A2aLifecycleService a2aLifecycleService;

    public AgentOrchestrator(
            ConversationStore conversationStore,
            GraphCheckpointStore checkpointStore,
            AgentStateGraphFactory graphFactory,
            ResponseComposeService responseComposeService,
            HumanMessageService humanMessageService,
            A2aLifecycleService a2aLifecycleService
    ) {
        this.conversationStore = conversationStore;
        this.checkpointStore = checkpointStore;
        this.graphFactory = graphFactory;
        this.responseComposeService = responseComposeService;
        this.humanMessageService = humanMessageService;
        this.a2aLifecycleService = a2aLifecycleService;
    }

    /**
     * 에이전트 실행의 최상위 진입점.
     * <p>
     * 처리 순서:
     * 1) 안내 문구 1개를 즉시 반환
     * 2) invokeGraph()로 계획/도구 실행 상태를 계산
     * 3) streamCompose()로 최종 답변 스트림 생성
     * 4) 스트림 종료 시 대화 이력/체크포인트 저장
     * <p>
     * TTFT 관점:
     * 실제 모델 토큰 스트림은 2) 완료 이후에 시작되므로,
     * invokeGraph()가 길어지면 첫 토큰도 함께 늦어진다.
     */
    public Flux<String> execute(AgentChatRequest request) {
        return Flux.concat(
                Flux.just("[생각의 과정 요약]\n- 요청을 분석하고 도구 사용 여부를 판단 중입니다.\n\n"),
                Mono.fromCallable(() -> invokeGraph(request))
                        .subscribeOn(Schedulers.boundedElastic())
                        .flatMapMany(context -> {
                            if (isCanceled(request.a2aContext())) {
                                return Flux.just("요청이 취소되었습니다.");
                            }
                            StringBuilder assistantResponse = new StringBuilder();
                            AtomicBoolean failed = new AtomicBoolean(false);
                            AtomicBoolean canceled = new AtomicBoolean(false);
                            return responseComposeService.streamCompose(context)
                                    .<String>handle((chunk, sink) -> {
                                        if (isCanceled(request.a2aContext())) {
                                            canceled.set(true);
                                            sink.next("요청이 취소되었습니다.");
                                            sink.complete();
                                            return;
                                        }
                                        sink.next(chunk);
                                    })
                                    .index()
                                    .onErrorResume(error -> {
                                        failed.set(true);
                                        markA2aFailed(request, error);
                                        return Flux.just(Tuples.of(1L, humanMessageService.fromException(error)));
                                    })
                                    .doOnNext(indexedChunk -> appendFinalAnswerOnly(assistantResponse, indexedChunk))
                                    .map(Tuple2::getT2)
                                    .doFinally(signalType -> {
                                        persist(context, assistantResponse.toString());
                                        if (!failed.get() && !canceled.get()) {
                                            markA2aCompleted(request, assistantResponse.toString());
                                        }
                                    });
                        })
                        .onErrorResume(error -> {
                            markA2aFailed(request, error);
                            return Flux.just(humanMessageService.fromException(error));
                        })
        );
    }

    public void clearSession(String sessionId) {
        conversationStore.clear(sessionId);
        checkpointStore.clear(sessionId);
    }

    /**
     * 세션 대화 이력의 메시지 수를 반환한다.
     */
    public int getMessageCount(String sessionId) {
        return conversationStore.load(sessionId).size();
    }

    /**
     * 실행 완료 후 사용자/어시스턴트 발화를 대화 저장소와 체크포인트 저장소에 반영한다.
     */
    private void persist(PlanningContext context, String assistantResponse) {
        List<String> updated = new ArrayList<>(context.getHistory());
        updated.add("user: " + context.getUserMessage());
        updated.add("assistant: " + assistantResponse);
        conversationStore.save(context.getSessionId(), updated);
        checkpointStore.saveCheckpoint(context.getSessionId(), "state=COMPLETED;at=" + Instant.now());
    }

    private void appendFinalAnswerOnly(StringBuilder assistantResponse, Tuple2<Long, String> indexedChunk) {
        // index 0은 trace summary(생각 요약)이며 실제 assistant 최종 응답에 저장하지 않는다.
        if (indexedChunk.getT1() == 0) {
            return;
        }
        String chunk = indexedChunk.getT2();
        if (chunk != null) {
            assistantResponse.append(chunk);
        }
    }

    /**
     * LangGraph 실행으로 PlanningContext를 생성한다.
     * <p>
     * 이 단계는 블로킹 가능성이 있으므로 boundedElastic에서 실행하며,
     * 스트림 시작 전 선행 단계이기 때문에 첫 토큰 지연에 직접 영향이 있다.
     */
    private PlanningContext invokeGraph(AgentChatRequest request) {
        List<String> history = conversationStore.load(request.sessionId());
        String checkpointId = checkpointStore.loadCheckpoint(request.sessionId()).orElse("");
        AgentScope scope = scope(request);

        CompiledGraph<AgentGraphState> graph = graphFactory.getCompiledGraph();
        RunnableConfig.Builder configBuilder = RunnableConfig.builder()
                .threadId(request.sessionId());
        if (!checkpointId.isBlank()) {
            configBuilder.checkPointId(checkpointId);
        }

        AgentGraphState state = graph.invoke(
                        Map.of(
                                AgentGraphState.SESSION_ID, request.sessionId(),
                                AgentGraphState.USER_MESSAGE, request.message(),
                                AgentGraphState.MODEL, request.model() == null ? "openai" : request.model(),
                                AgentGraphState.HISTORY, history,
                                AgentGraphState.CHECKPOINT_ID, checkpointId,
                                AgentGraphState.CURRENT_NODE, "HISTORY_LOADED",
                                AgentGraphState.SCOPE_ALLOWED_SERVERS, List.copyOf(scope.allowedServers()),
                                AgentGraphState.SCOPE_ALLOWED_TOOLS, toScopeMap(scope),
                                AgentGraphState.SCOPE_UNRESTRICTED, scope.isUnrestricted()
                        ),
                        configBuilder.build()
                )
                .orElseGet(() -> new AgentGraphState(Map.of(
                        AgentGraphState.SESSION_ID, request.sessionId(),
                        AgentGraphState.USER_MESSAGE, request.message(),
                        AgentGraphState.MODEL, request.model() == null ? "openai" : request.model(),
                        AgentGraphState.HISTORY, history,
                        AgentGraphState.CHECKPOINT_ID, checkpointId,
                        AgentGraphState.SCOPE_ALLOWED_SERVERS, List.copyOf(scope.allowedServers()),
                        AgentGraphState.SCOPE_ALLOWED_TOOLS, toScopeMap(scope),
                        AgentGraphState.SCOPE_UNRESTRICTED, scope.isUnrestricted()
                )));

        return state.toPlanningContext();
    }

    private AgentScope scope(AgentChatRequest request) {
        if (request.scope() == null) {
            return AgentScope.unrestricted();
        }
        return request.scope();
    }

    /**
     * 스코프 허용 도구 맵을 불변 복사본으로 변환한다.
     */
    private Map<String, List<String>> toScopeMap(AgentScope scope) {
        Map<String, List<String>> mapped = new LinkedHashMap<>();
        scope.allowedToolsByServer().forEach((server, tools) -> mapped.put(server, List.copyOf(tools)));
        return mapped;
    }

    /**
     * A2A 요청인 경우 작업을 완료 상태로 마킹한다.
     */
    private void markA2aCompleted(AgentChatRequest request, String response) {
        A2aExecutionContext context = request.a2aContext();
        if (context == null || context.taskId() == null || context.taskId().isBlank()) {
            return;
        }
        if (isCanceled(context)) {
            return;
        }
        a2aLifecycleService.markCompleted(context.taskId(), context.scopeName(), response == null ? "" : response);
    }

    /**
     * A2A 요청인 경우 작업을 실패 상태로 마킹한다.
     */
    private void markA2aFailed(AgentChatRequest request, Throwable error) {
        A2aExecutionContext context = request.a2aContext();
        if (context == null || context.taskId() == null || context.taskId().isBlank()) {
            return;
        }
        String message = error == null ? "A2A task failed" : error.getMessage();
        a2aLifecycleService.markFailed(context.taskId(), context.scopeName(), "INTERNAL_ERROR", message);
    }

    /**
     * A2A task가 취소 상태인지 조회한다.
     */
    private boolean isCanceled(A2aExecutionContext context) {
        if (context == null || context.taskId() == null || context.taskId().isBlank()) {
            return false;
        }
        return a2aLifecycleService.get(context.taskId(), context.scopeName())
                .map(snapshot -> snapshot.status() == A2aTaskStatus.CANCELED)
                .orElse(false);
    }
}
