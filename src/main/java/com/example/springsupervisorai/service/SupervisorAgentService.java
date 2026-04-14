package com.example.springsupervisorai.service;

import com.example.springsupervisorai.a2a.A2AResponseMapper;
import com.example.springsupervisorai.a2a.dto.JsonRpcResponse;
import com.example.springsupervisorai.a2a.dto.TaskView;
import com.example.springsupervisorai.a2a.idempotency.SupervisorRequestIdempotencyService;
import com.example.springsupervisorai.a2a.lifecycle.SupervisorA2aLifecycleService;
import com.example.springsupervisorai.a2a.task.A2aTaskSnapshot;
import com.example.springsupervisorai.model.HitlDecisionType;
import com.example.springsupervisorai.model.HitlPolicyResult;
import com.example.springsupervisorai.model.SupervisorAgentRequest;
import com.example.springsupervisorai.model.HitlReviewTicket;
import com.example.springsupervisorai.service.agent.hitl.HitlDecisionService;
import com.example.springsupervisorai.service.agent.hitl.HitlPolicyService;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.core.scheduler.Schedulers;

import java.util.Locale;
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
    private final SupervisorA2aLifecycleService lifecycleService;
    private final A2AResponseMapper responseMapper;
    private final SupervisorRequestIdempotencyService requestIdempotencyService;
    private final HitlPolicyService hitlPolicyService;
    private final HitlDecisionService hitlDecisionService;

    /**
     * 서비스 의존성을 생성자 주입으로 초기화한다.
     *
     * @param orchestrator supervisor 오케스트레이터
     * @param lifecycleService supervisor task 라이프사이클 서비스
     * @param responseMapper task snapshot 응답 매퍼
     */
    public SupervisorAgentService(
            SupervisorAgentOrchestrator orchestrator,
            SupervisorA2aLifecycleService lifecycleService,
            A2AResponseMapper responseMapper,
            SupervisorRequestIdempotencyService requestIdempotencyService,
            HitlPolicyService hitlPolicyService,
            HitlDecisionService hitlDecisionService
    ) {
        this.orchestrator = orchestrator;
        this.lifecycleService = lifecycleService;
        this.responseMapper = responseMapper;
        this.requestIdempotencyService = requestIdempotencyService;
        this.hitlPolicyService = hitlPolicyService;
        this.hitlDecisionService = hitlDecisionService;
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
        HitlPolicyResult policyResult = hitlPolicyService.evaluate(sessionId, message, model);
        if (policyResult.required()) {
            return buildWaitingReviewResponse(requestId, sessionId, message, model, policyResult);
        }

        A2aTaskSnapshot task = lifecycleService.createAndMarkRunning(sessionId, message);
        String payload = collectOrchestrationPayload(new SupervisorAgentRequest(sessionId, message, model), task.taskId());
        TaskView view = resolveTaskViewAfterSend(task, payload);
        return JsonRpcResponse.success(requestId, view);
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
        return Flux.concat(
                initialStreamProgress(sessionId),
                evaluateHitlPolicyAsync(sessionId, message, model)
                        .flatMapMany(policyResult -> policyResult.required()
                                ? streamWhenHitlRequired(sessionId, message, model, policyResult)
                                : streamWhenHitlPassed(sessionId, message, model))
        );
    }

    private String progressLine(String stage, int progress, String message, Map<String, Object> metadata) {
        return SupervisorProgressSupport.line(stage, progress, message, metadata);
    }

    /**
     * task 단건 조회를 수행한다.
     *
     * @param taskId task id
     * @return task snapshot(optional)
     */
    public Optional<A2aTaskSnapshot> getTask(String taskId, String sessionId) {
        return lifecycleService.get(taskId, sessionId);
    }

    /**
     * task 취소를 수행한다.
     *
     * @param taskId task id
     * @param reason 취소 사유
     * @return 취소된 task snapshot(optional)
     */
    public Optional<A2aTaskSnapshot> cancelTask(String taskId, String sessionId, String reason) {
        return lifecycleService.cancel(taskId, sessionId, reason);
    }

    /**
     * task 목록을 제한 건수로 조회한다.
     *
     * @param limit 최대 조회 건수
     * @return task snapshot 목록
     */
    public java.util.List<A2aTaskSnapshot> listTasks(String sessionId, int limit) {
        return lifecycleService.list(sessionId, limit);
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
        return hitlDecisionService.getReview(taskId, sessionId);
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
            String decisionId
    ) {
        HitlDecisionType decisionType = parseDecisionType(decision);
        if (decisionType == null) {
            return Optional.empty();
        }
        Optional<HitlReviewTicket> decided = hitlDecisionService.decide(taskId, sessionId, decisionType, reason, decisionId);
        if (decided.isEmpty()) {
            return Optional.empty();
        }
        HitlReviewTicket ticket = decided.get();
        if (decisionType == HitlDecisionType.CANCEL) {
            return handleCancelDecision(sessionId, taskId, reason, ticket);
        }

        return handleApproveDecision(sessionId, taskId, ticket);
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
        A2aTaskSnapshot waitingTask = lifecycleService.createAndMarkWaitingReview(sessionId, message, policyResult.reason());
        hitlDecisionService.openReview(waitingTask.taskId(), sessionId, message, model, policyResult);
        TaskView waitingView = responseMapper.toTaskView(waitingTask);
        return JsonRpcResponse.success(requestId, waitingView);
    }

    /**
     * 오케스트레이터 응답 스트림을 단일 payload 문자열로 집계한다.
     */
    private String collectOrchestrationPayload(SupervisorAgentRequest request, String taskId) {
        return orchestrator.execute(request, taskId)
                .collectList()
                .map(chunks -> String.join("", chunks))
                .blockOptional()
                .orElse("");
    }

    /**
     * send 처리 후 최신 task 상태를 조회해 응답 view를 생성한다.
     */
    private TaskView resolveTaskViewAfterSend(A2aTaskSnapshot task, String payload) {
        Optional<A2aTaskSnapshot> latest = lifecycleService.get(task.taskId());
        TaskView view = responseMapper.toTaskView(latest.orElse(task));
        if (payload != null && !payload.isBlank() && latest.isEmpty()) {
            lifecycleService.markCompleted(task.taskId(), payload);
            return responseMapper.toTaskView(lifecycleService.get(task.taskId()).orElse(task));
        }
        return view;
    }

    /**
     * stream 시작 시 HITL 정책 평가 안내 메시지를 생성한다.
     */
    private Flux<String> initialStreamProgress(String sessionId) {
        return Flux.just(
                progressLine(SupervisorProgressSupport.STAGE_HITL, 2, "HITL 정책 평가를 시작합니다.", Map.of(
                        "sessionId", sessionId == null ? "" : sessionId
                ))
        );
    }

    /**
     * HITL 정책 평가를 비동기 스케줄러에서 수행한다.
     */
    private Mono<HitlPolicyResult> evaluateHitlPolicyAsync(String sessionId, String message, String model) {
        return Mono.fromCallable(() -> hitlPolicyService.evaluate(sessionId, message, model))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * stream 요청이 HITL 대기 상태로 귀결되는 경우의 진행 이벤트를 생성한다.
     */
    private Flux<String> streamWhenHitlRequired(
            String sessionId,
            String message,
            String model,
            HitlPolicyResult policyResult
    ) {
        A2aTaskSnapshot waitingTask = lifecycleService.createAndMarkWaitingReview(sessionId, message, policyResult.reason());
        hitlDecisionService.openReview(waitingTask.taskId(), sessionId, message, model, policyResult);
        return Flux.just(
                progressLine(SupervisorProgressSupport.STAGE_HITL, 5, "HITL 정책에서 사용자 승인 필요로 판단되었습니다.", Map.of(
                        "policyId", policyResult.policyId(),
                        "reason", policyResult.reason()
                )),
                progressLine(SupervisorProgressSupport.STAGE_HITL_WAITING, 8, policyResult.reason(), Map.of(
                        "taskId", waitingTask.taskId(),
                        "reviewStatus", "WAITING_REVIEW",
                        "policyId", policyResult.policyId(),
                        "reason", policyResult.reason()
                ))
        );
    }

    /**
     * stream 요청이 HITL 통과된 경우 오케스트레이션 실행 스트림을 생성한다.
     */
    private Flux<String> streamWhenHitlPassed(String sessionId, String message, String model) {
        A2aTaskSnapshot task = lifecycleService.createAndMarkRunning(sessionId, message);
        Flux<String> acceptedProgress = Flux.just(
                progressLine(SupervisorProgressSupport.STAGE_HITL, 5, "HITL 정책 평가를 통과했습니다. 오케스트레이션을 계속합니다.", Map.of(
                        "policy", "PASSED"
                ))
        );
        return Flux.concat(acceptedProgress, orchestrator.execute(new SupervisorAgentRequest(sessionId, message, model), task.taskId()))
                .doFinally(signalType -> {
                    if (signalType == SignalType.CANCEL) {
                        lifecycleService.cancel(task.taskId(), "Stream canceled");
                    }
                });
    }

    /**
     * review 결정 문자열을 enum으로 변환한다.
     */
    private HitlDecisionType parseDecisionType(String decision) {
        return HitlDecisionType.from(decision.toUpperCase(Locale.ROOT)).orElse(null);
    }

    /**
     * CANCEL review 결정을 반영한다.
     */
    private Optional<Map<String, Object>> handleCancelDecision(
            String sessionId,
            String taskId,
            String reason,
            HitlReviewTicket ticket
    ) {
        lifecycleService.cancel(taskId, sessionId, reason == null ? "Canceled by reviewer" : reason);
        return buildDecisionResult(sessionId, taskId, ticket);
    }

    /**
     * APPROVE review 결정을 반영하고 오케스트레이션을 재개한다.
     */
    private Optional<Map<String, Object>> handleApproveDecision(
            String sessionId,
            String taskId,
            HitlReviewTicket ticket
    ) {
        lifecycleService.markRunning(taskId);
        String payload = collectOrchestrationPayload(new SupervisorAgentRequest(sessionId, ticket.message(), ticket.model()), taskId);
        lifecycleService.markCompleted(taskId, payload);
        return buildDecisionResult(sessionId, taskId, ticket);
    }

    /**
     * review 결정 결과(task/review)를 응답 포맷으로 조합한다.
     */
    private Optional<Map<String, Object>> buildDecisionResult(String sessionId, String taskId, HitlReviewTicket ticket) {
        return lifecycleService.get(taskId, sessionId)
                .map(snapshot -> Map.<String, Object>of(
                        "task", responseMapper.toTaskView(snapshot),
                        "review", responseMapper.toTaskReviewView(ticket)
                ));
    }
}
