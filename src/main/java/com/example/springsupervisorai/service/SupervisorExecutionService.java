package com.example.springsupervisorai.service;

import com.example.springsupervisorai.a2a.task.A2aTaskSnapshot;
import com.example.springsupervisorai.a2a.task.A2aTaskStatus;
import com.example.springsupervisorai.model.SupervisorExecutionRequest;
import com.example.springsupervisorai.model.SupervisorOutputEvent;
import com.example.springsupervisorai.service.agent.invoke.A2AInvocationService;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

/**
 * 실행 가능한 supervisor 요청을 오케스트레이터에 위임하는 서비스.
 */
@Service
public class SupervisorExecutionService {

    private final SupervisorAgentOrchestrator orchestrator;
    private final SupervisorTaskFacade taskFacade;
    private final SupervisorExecutionResultCollector executionResultCollector;
    private final A2AInvocationService a2AInvocationService;

    public SupervisorExecutionService(
            SupervisorAgentOrchestrator orchestrator,
            SupervisorTaskFacade taskFacade,
            SupervisorExecutionResultCollector executionResultCollector,
            A2AInvocationService a2AInvocationService
    ) {
        this.orchestrator = orchestrator;
        this.taskFacade = taskFacade;
        this.executionResultCollector = executionResultCollector;
        this.a2AInvocationService = a2AInvocationService;
    }

    /**
     * 동기 send 요청을 실행하고 최종 task snapshot을 반환한다.
     *
     * @param request 실행 요청
     * @return 최신 task snapshot
     */
    public A2aTaskSnapshot executeSync(SupervisorExecutionRequest request) {
        A2aTaskSnapshot task = taskFacade.createRunningTask(request.sessionId(), request.message());
        SupervisorExecutionResultCollector.SupervisorExecutionResult result = collectExecutionResult(request, task.taskId());
        A2aTaskSnapshot latest = taskFacade.getTask(task.taskId()).orElse(null);
        if (isTerminal(latest)) {
            return latest;
        }
        String payload = result.taskPayload();
        if (!payload.isBlank()) {
            taskFacade.markCompleted(task.taskId(), payload);
        }
        return taskFacade.getTask(task.taskId()).orElse(task);
    }

    /**
     * stream 요청을 실행한다.
     *
     * @param request 실행 요청
     * @return 사용자 응답 스트림
     */
    public Flux<String> executeStream(SupervisorExecutionRequest request) {
        return executeStreamEvents(request).map(SupervisorOutputEventSupport::serialize);
    }

    /**
     * stream 요청을 구조화된 이벤트 스트림으로 실행한다.
     *
     * @param request 실행 요청
     * @return supervisor 출력 이벤트 스트림
     */
    public Flux<SupervisorOutputEvent> executeStreamEvents(SupervisorExecutionRequest request) {
        A2aTaskSnapshot task = taskFacade.createRunningTask(request.sessionId(), request.message());
        return orchestrator.executeEvents(request.toAgentRequest(), task.taskId())
                .doFinally(signalType -> {
                    if (signalType == SignalType.CANCEL) {
                        a2AInvocationService.cancelDownstream(request.sessionId());
                        taskFacade.cancelTask(task.taskId(), "Stream canceled");
                    }
                });
    }

    /**
     * review 승인 후 task를 재실행한다.
     *
     * @param taskId task id
     * @param request 실행 요청
     */
    public void resumeApprovedTask(String taskId, SupervisorExecutionRequest request) {
        taskFacade.markRunning(taskId);
        SupervisorExecutionResultCollector.SupervisorExecutionResult result = collectExecutionResult(request, taskId);
        A2aTaskSnapshot latest = taskFacade.getTask(taskId).orElse(null);
        if (isTerminal(latest)) {
            return;
        }
        taskFacade.markCompleted(taskId, result.taskPayload());
    }

    /**
     * review 승인 후 task를 스트리밍으로 재실행한다.
     * <p>
     * 기존 approve unary와 달리 같은 taskId로 progress/text 이벤트를 즉시 반환한다.
     * 클라이언트 연결이 끊겨도 승인 사실 자체는 유지되어야 하므로 cancel 전이는 하지 않는다.
     *
     * @param taskId task id
     * @param request 실행 요청
     * @return supervisor 출력 이벤트 스트림
     */
    public Flux<SupervisorOutputEvent> resumeApprovedTaskStream(String taskId, SupervisorExecutionRequest request) {
        taskFacade.markRunning(taskId);
        return orchestrator.executeEvents(request.toAgentRequest(), taskId);
    }

    /**
     * sync/resume 실행용 결과를 수집한다.
     *
     * @param request 실행 요청
     * @param taskId task id
     * @return persistence용 구조화 결과
     */
    private SupervisorExecutionResultCollector.SupervisorExecutionResult collectExecutionResult(
            SupervisorExecutionRequest request,
            String taskId
    ) {
        return executionResultCollector.collect(orchestrator.executeEvents(request.toAgentRequest(), taskId));
    }

    private boolean isTerminal(A2aTaskSnapshot snapshot) {
        if (snapshot == null || snapshot.status() == null) {
            return false;
        }
        return snapshot.status() == A2aTaskStatus.COMPLETED
                || snapshot.status() == A2aTaskStatus.FAILED
                || snapshot.status() == A2aTaskStatus.CANCELED;
    }
}
