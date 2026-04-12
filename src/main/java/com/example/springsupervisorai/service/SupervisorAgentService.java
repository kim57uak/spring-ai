package com.example.springsupervisorai.service;

import com.example.springsupervisorai.a2a.A2AResponseMapper;
import com.example.springsupervisorai.a2a.dto.JsonRpcResponse;
import com.example.springsupervisorai.a2a.dto.TaskView;
import com.example.springsupervisorai.a2a.idempotency.SupervisorRequestIdempotencyService;
import com.example.springsupervisorai.a2a.lifecycle.SupervisorA2aLifecycleService;
import com.example.springsupervisorai.a2a.task.A2aTaskSnapshot;
import com.example.springsupervisorai.model.SupervisorAgentRequest;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

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
            SupervisorRequestIdempotencyService requestIdempotencyService
    ) {
        this.orchestrator = orchestrator;
        this.lifecycleService = lifecycleService;
        this.responseMapper = responseMapper;
        this.requestIdempotencyService = requestIdempotencyService;
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
        A2aTaskSnapshot task = lifecycleService.createAndMarkRunning(sessionId, message);
        String payload = orchestrator.execute(new SupervisorAgentRequest(sessionId, message, model), task.taskId())
                .collectList()
                .map(chunks -> String.join("", chunks))
                .blockOptional()
                .orElse("");

        Optional<A2aTaskSnapshot> latest = lifecycleService.get(task.taskId());
        TaskView view = responseMapper.toTaskView(latest.orElse(task));
        if (payload != null && !payload.isBlank() && latest.isEmpty()) {
            lifecycleService.markCompleted(task.taskId(), payload);
            view = responseMapper.toTaskView(lifecycleService.get(task.taskId()).orElse(task));
        }
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
        A2aTaskSnapshot task = lifecycleService.createAndMarkRunning(sessionId, message);
        return orchestrator.execute(new SupervisorAgentRequest(sessionId, message, model), task.taskId())
                .doFinally(signalType -> {
                    if (signalType == SignalType.CANCEL) {
                        lifecycleService.cancel(task.taskId(), "Stream canceled");
                    }
                });
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
}
