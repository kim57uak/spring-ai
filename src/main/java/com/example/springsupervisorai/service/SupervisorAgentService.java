package com.example.springsupervisorai.service;

import com.example.springsupervisorai.a2a.A2AResponseMapper;
import com.example.springsupervisorai.a2a.dto.JsonRpcResponse;
import com.example.springsupervisorai.a2a.dto.TaskView;
import com.example.springsupervisorai.a2a.idempotency.SupervisorRequestIdempotencyService;
import com.example.springsupervisorai.a2a.task.A2aTaskSnapshot;
import com.example.springsupervisorai.model.HitlPolicyResult;
import com.example.springsupervisorai.model.HitlReviewTicket;
import com.example.springsupervisorai.model.SupervisorExecutionRequest;
import com.example.springsupervisorai.model.SupervisorOutputEvent;
import com.example.springsupervisorai.service.agent.a2ui.common.SupervisorA2uiService;
import com.example.springsupervisorai.service.agent.a2ui.common.SupervisorA2uiSupport;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;
import java.util.Optional;

/**
 * Supervisor 유스케이스 애플리케이션 서비스.
 * <p>
 * 컨트롤러에서 받은 JSON-RPC 요청을
 * 오케스트레이터 실행/Task 조회·취소 API로 매핑한다.
 */
@Service
public class SupervisorAgentService {

    private final SupervisorAgentOrchestrator orchestrator;
    private final SupervisorTaskFacade taskFacade;
    private final A2AResponseMapper responseMapper;
    private final SupervisorRequestIdempotencyService requestIdempotencyService;
    private final HitlGateService hitlGateService;
    private final SupervisorExecutionService executionService;
    private final SupervisorReviewApplicationService reviewApplicationService;
    private final SupervisorStreamProgressService streamProgressService;
    private final SupervisorPreHitlA2uiService preHitlA2uiService;

    /**
     * 서비스 의존성을 생성자 주입으로 초기화한다.
     *
     * @param orchestrator supervisor 오케스트레이터
     * @param taskFacade supervisor task facade
     * @param responseMapper task snapshot 응답 매퍼
     */
    public SupervisorAgentService(
            SupervisorAgentOrchestrator orchestrator,
            SupervisorTaskFacade taskFacade,
            A2AResponseMapper responseMapper,
            SupervisorRequestIdempotencyService requestIdempotencyService,
            HitlGateService hitlGateService,
            SupervisorExecutionService executionService,
            SupervisorReviewApplicationService reviewApplicationService,
            SupervisorStreamProgressService streamProgressService,
            SupervisorPreHitlA2uiService preHitlA2uiService
    ) {
        this.orchestrator = orchestrator;
        this.taskFacade = taskFacade;
        this.responseMapper = responseMapper;
        this.requestIdempotencyService = requestIdempotencyService;
        this.hitlGateService = hitlGateService;
        this.executionService = executionService;
        this.reviewApplicationService = reviewApplicationService;
        this.streamProgressService = streamProgressService;
        this.preHitlA2uiService = preHitlA2uiService;
    }

    /**
     * SendMessage/message-send 계열 요청을 동기 응답으로 처리한다.
     *
     * @param requestId JSON-RPC request id
     * @param sessionId 사용자 세션 id
     * @param message 사용자 메시지
     * @param model 모델 식별자
     * @return task view를 담은 JSON-RPC 성공 응답
     */
    public JsonRpcResponse send(Object requestId, String sessionId, String message, String model, String requestMethod) {
        return requestIdempotencyService.executeOnce(
                sessionId,
                normalizeSendMethod(requestMethod),
                requestId,
                () -> executeSend(requestId, sessionId, message, model)
        );
    }

    /**
     * idempotency 키용 메서드명을 정규화한다.
     * <p>
     * 목적:
     * - `SendMessage`와 `message/send`를 동일 요청 의미로 간주하여
     *   중복 실행 방지 캐시를 공유한다.
     *
     * @param requestMethod 원본 메서드명
     * @return 정규화된 메서드 키
     */
    private String normalizeSendMethod(String requestMethod) {
        if (requestMethod == null || requestMethod.isBlank()) {
            return "send-message";
        }
        return switch (requestMethod) {
            case "SendMessage", "message/send" -> "send-message";
            default -> requestMethod;
        };
    }

    private JsonRpcResponse executeSend(Object requestId, String sessionId, String message, String model) {
        Optional<SupervisorA2uiService.A2uiRenderResult> preHitlA2ui = preHitlA2uiService.build(sessionId, message, model);
        if (preHitlA2ui.isPresent()) {
            A2aTaskSnapshot task = taskFacade.createRunningTask(sessionId, message);
            String payload = preHitlA2ui.get().message() + "\n" + SupervisorA2uiSupport.wrap(preHitlA2ui.get().protocolPayloadJson());
            taskFacade.markCompleted(task.taskId(), payload);
            return JsonRpcResponse.success(
                    requestId,
                    responseMapper.toTaskView(taskFacade.getTask(task.taskId()).orElse(task))
            );
        }

        HitlPolicyResult policyResult = hitlGateService.evaluate(sessionId, message, model);
        if (policyResult.required()) {
            return buildWaitingReviewResponse(requestId, sessionId, message, model, policyResult);
        }

        A2aTaskSnapshot task = executionService.executeSync(new SupervisorExecutionRequest(sessionId, message, model != null ? model : "claude-3"));
        return JsonRpcResponse.success(requestId, responseMapper.toTaskView(task));
    }

    /**
     * SendStreamingMessage/message-stream 계열 요청을 SSE 토큰 스트림으로 처리한다.
     *
     * @param sessionId 사용자 세션 id
     * @param message 사용자 메시지
     * @param model 모델 식별자
     * @return 응답 토큰 Flux
     */
    public Flux<String> stream(String sessionId, String message, String model) {
        return streamEvents(sessionId, message, model).map(SupervisorOutputEventSupport::serialize);
    }

    /**
     * SendStreamingMessage/message-stream 계열 요청을 구조화된 이벤트 스트림으로 처리한다.
     *
     * @param sessionId 사용자 세션 id
     * @param message 사용자 메시지
     * @param model 모델 식별자
     * @return 구조화된 supervisor output event flux
     */
    public Flux<SupervisorOutputEvent> streamEvents(String sessionId, String message, String model) {
        Optional<SupervisorA2uiService.A2uiRenderResult> preHitlA2ui = preHitlA2uiService.build(sessionId, message, model);
        if (preHitlA2ui.isPresent()) {
            return Flux.concat(
                    streamProgressService.preHitlA2uiEvents(),
                    Flux.just(
                            SupervisorOutputEvent.text(preHitlA2ui.get().message()),
                            SupervisorOutputEvent.a2ui(preHitlA2ui.get().protocolPayloadJson())
                    )
            );
        }
        return Flux.concat(
                streamProgressService.initialHitlEvaluationEvents(sessionId),
                evaluateHitlPolicyAsync(sessionId, message, model)
                        .flatMapMany(policyResult -> policyResult.required()
                                ? streamWhenHitlRequired(sessionId, message, model, policyResult)
                                : streamWhenHitlPassed(sessionId, message, model))
        );
    }

    /**
     * task 단건 조회를 수행한다.
     *
     * @param taskId task id
     * @return task snapshot(optional)
     */
    public Optional<A2aTaskSnapshot> getTask(String taskId, String sessionId) {
        return taskFacade.getTask(taskId, sessionId);
    }

    /**
     * task 취소를 수행한다.
     *
     * @param taskId task id
     * @param reason 취소 사유
     * @return 취소된 task snapshot(optional)
     */
    public Optional<A2aTaskSnapshot> cancelTask(String taskId, String sessionId, String reason) {
        return taskFacade.cancelTask(taskId, sessionId, reason);
    }

    /**
     * task 목록을 제한 건수로 조회한다.
     *
     * @param limit 최대 조회 건수
     * @return task snapshot 목록
     */
    public java.util.List<A2aTaskSnapshot> listTasks(String sessionId, int limit) {
        return taskFacade.listTasks(sessionId, limit);
    }

    /**
     * 세션 히스토리/체크포인트를 초기화한다.
     *
     * @param sessionId 세션 id
     */
    public void clearSession(String sessionId) {
        orchestrator.clearSession(sessionId);
    }

    /**
     * HITL review 정보를 조회한다.
     *
     * @param taskId task id
     * @param sessionId 호출자 세션 id
     * @return review 티켓(optional)
     */
    public Optional<HitlReviewTicket> getReview(String taskId, String sessionId) {
        return hitlGateService.getReview(taskId, sessionId);
    }

    /**
     * HITL review 결정을 반영한다.
     * <p>
     * - CANCEL: task를 취소 상태로 종료한다.
     * - APPROVE: 대기 task를 실행 상태로 전환하고 오케스트레이션을 재개한다.
     *
     * @param sessionId 호출자 세션 id
     * @param taskId task id
     * @param decision 결정 문자열(APPROVE/CANCEL)
     * @param reason 결정 사유
     * @param decisionId 결정 idempotency id
     * @return task/review 결과 맵(optional)
     */
    public Optional<Map<String, Object>> decideReview(
            String sessionId,
            String taskId,
            String decision,
            String reason,
            String decisionId,
            String revisedMessage
    ) {
        return reviewApplicationService.decideReview(sessionId, taskId, decision, reason, decisionId, revisedMessage);
    }

    /**
     * HITL review 결정을 반영하고, 필요 시 후속 실행을 스트리밍한다.
     *
     * @param sessionId 호출자 세션 id
     * @param taskId task id
     * @param decision 결정 문자열(APPROVE/CANCEL)
     * @param reason 결정 사유
     * @param decisionId 결정 idempotency id
     * @return 구조화된 supervisor output event flux
     */
    public Flux<SupervisorOutputEvent> decideReviewStream(
            String sessionId,
            String taskId,
            String decision,
            String reason,
            String decisionId,
            String revisedMessage
    ) {
        return reviewApplicationService.decideReviewStream(sessionId, taskId, decision, reason, decisionId, revisedMessage);
    }

    /**
     * HITL 요청이 필요한 경우 대기 상태 task/view 응답을 생성한다.
     */
    private JsonRpcResponse buildWaitingReviewResponse(
            Object requestId,
            String sessionId,
            String message,
            String model,
            HitlPolicyResult policyResult
    ) {
        A2aTaskSnapshot waitingTask = hitlGateService.openReview(sessionId, message, model, policyResult);
        TaskView waitingView = responseMapper.toTaskView(waitingTask);
        return JsonRpcResponse.success(requestId, waitingView);
    }

    /**
     * HITL 정책 평가를 비동기 스케줄러에서 수행한다.
     */
    private Mono<HitlPolicyResult> evaluateHitlPolicyAsync(String sessionId, String message, String model) {
        return Mono.fromCallable(() -> hitlGateService.evaluate(sessionId, message, model))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * stream 요청이 HITL 대기 상태로 귀결되는 경우의 진행 이벤트를 생성한다.
     */
    private Flux<SupervisorOutputEvent> streamWhenHitlRequired(
            String sessionId,
            String message,
            String model,
            HitlPolicyResult policyResult
    ) {
        A2aTaskSnapshot waitingTask = hitlGateService.openReview(sessionId, message, model, policyResult);
        return streamProgressService.hitlRequiredEvents(policyResult, waitingTask);
    }

    /**
     * stream 요청이 HITL 통과된 경우 오케스트레이션 실행 스트림을 생성한다.
     */
    private Flux<SupervisorOutputEvent> streamWhenHitlPassed(String sessionId, String message, String model) {
        return Flux.concat(
                streamProgressService.hitlPassedEvents(),
                executionService.executeStreamEvents(new SupervisorExecutionRequest(sessionId, message, model != null ? model : "claude-3"))
        );
    }
}
