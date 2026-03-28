package com.example.springai.service.agent.orchestrator;

import com.example.springai.model.agent.AgentChatRequest;
import com.example.springai.model.agent.AgentGraphState;
import com.example.springai.model.agent.PlanningContext;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class AgentOrchestrator {

    private final ConversationStore conversationStore;
    private final GraphCheckpointStore checkpointStore;
    private final AgentStateGraphFactory graphFactory;
    private final ResponseComposeService responseComposeService;
    private final HumanMessageService humanMessageService;

    public AgentOrchestrator(
            ConversationStore conversationStore,
            GraphCheckpointStore checkpointStore,
            AgentStateGraphFactory graphFactory,
            ResponseComposeService responseComposeService,
            HumanMessageService humanMessageService
    ) {
        this.conversationStore = conversationStore;
        this.checkpointStore = checkpointStore;
        this.graphFactory = graphFactory;
        this.responseComposeService = responseComposeService;
        this.humanMessageService = humanMessageService;
    }

    public Flux<String> execute(AgentChatRequest request) {
        return Flux.concat(
                Flux.just("[생각의 과정 요약]\n- 요청을 분석하고 도구 사용 여부를 판단 중입니다.\n\n"),
                Mono.fromCallable(() -> invokeGraph(request))
                        .subscribeOn(Schedulers.boundedElastic())
                        .flatMapMany(context -> {
                            StringBuilder assistantResponse = new StringBuilder();
                            return responseComposeService.streamCompose(context)
                                    .doOnNext(assistantResponse::append)
                                    .onErrorResume(error -> Flux.just(humanMessageService.fromException(error)))
                                    .doFinally(signalType -> persist(context, assistantResponse.toString()));
                        })
                        .onErrorResume(error -> Flux.just(humanMessageService.fromException(error)))
        );
    }

    public void clearSession(String sessionId) {
        conversationStore.clear(sessionId);
        checkpointStore.clear(sessionId);
    }

    public int getMessageCount(String sessionId) {
        return conversationStore.load(sessionId).size();
    }

    private void persist(PlanningContext context, String assistantResponse) {
        List<String> updated = new ArrayList<>(context.getHistory());
        updated.add("user: " + context.getUserMessage());
        updated.add("assistant: " + assistantResponse);
        conversationStore.save(context.getSessionId(), updated);
        checkpointStore.saveCheckpoint(context.getSessionId(), "state=COMPLETED;at=" + Instant.now());
    }

    private PlanningContext invokeGraph(AgentChatRequest request) {
        List<String> history = conversationStore.load(request.sessionId());
        String checkpointId = checkpointStore.loadCheckpoint(request.sessionId()).orElse("");

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
                                AgentGraphState.MODEL, request.model() == null ? "mistral" : request.model(),
                                AgentGraphState.HISTORY, history,
                                AgentGraphState.CHECKPOINT_ID, checkpointId,
                                AgentGraphState.CURRENT_NODE, "HISTORY_LOADED"
                        ),
                        configBuilder.build()
                )
                .orElseGet(() -> new AgentGraphState(Map.of(
                        AgentGraphState.SESSION_ID, request.sessionId(),
                        AgentGraphState.USER_MESSAGE, request.message(),
                        AgentGraphState.MODEL, request.model() == null ? "mistral" : request.model(),
                        AgentGraphState.HISTORY, history,
                        AgentGraphState.CHECKPOINT_ID, checkpointId
                )));

        return state.toPlanningContext();
    }
}
