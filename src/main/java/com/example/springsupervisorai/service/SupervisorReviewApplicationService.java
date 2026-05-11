package com.example.springsupervisorai.service;

import com.example.springsupervisorai.a2a.A2AResponseMapper;
import com.example.springsupervisorai.a2a.dto.TaskReviewView;
import com.example.springsupervisorai.a2a.dto.TaskView;
import com.example.springsupervisorai.a2a.task.A2aTaskSnapshot;
import com.example.springsupervisorai.model.HitlDecisionType;
import com.example.springsupervisorai.model.HitlReviewTicket;
import com.example.springsupervisorai.model.SupervisorOutputEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * HITL review decision 유스케이스를 담당하는 애플리케이션 서비스.
 * <p>
 * `SupervisorAgentService`에서 review decision 분기와 상태 전이 세부를 분리한다.
 */
@Service
public class SupervisorReviewApplicationService {

        private final HitlGateService hitlGateService;
        private final SupervisorTaskFacade taskFacade;
        private final SupervisorExecutionService executionService;
        private final A2AResponseMapper responseMapper;

        public SupervisorReviewApplicationService(
                        HitlGateService hitlGateService,
                        SupervisorTaskFacade taskFacade,
                        SupervisorExecutionService executionService,
                        A2AResponseMapper responseMapper) {
                this.hitlGateService = hitlGateService;
                this.taskFacade = taskFacade;
                this.executionService = executionService;
                this.responseMapper = responseMapper;
        }

        /**
         * review 결정을 반영하고 task/review 응답 결과를 구성한다.
         *
         * @param sessionId  호출자 세션 id
         * @param taskId     task id
         * @param decision   결정 문자열(APPROVE/CANCEL)
         * @param reason     결정 사유
         * @param decisionId 결정 idempotency id
         * @return task/review 결과 맵(optional)
         */
        public Optional<Map<String, Object>> decideReview(
                        String sessionId,
                        String taskId,
                        String decision,
                        String reason,
                        String decisionId,
                        String revisedMessage) {
                HitlDecisionType decisionType = parseDecisionType(decision);
                if (decisionType == null) {
                        return Optional.empty();
                }

                if (decisionType == HitlDecisionType.REVISE) {
                        A2aTaskSnapshot task = taskFacade.getTask(taskId, sessionId)
                                        .orElseThrow(() -> new IllegalArgumentException("Task not found"));
                        // 사용자 입력 반영
                        taskFacade.updateTaskMessage(taskId, revisedMessage);
                        // HITL 티켓에 revisedMessage 반영
                        hitlGateService.decide(taskId, sessionId, HitlDecisionType.REVISE, "Revised by user",
                                        decisionId, revisedMessage);
                        // 재실행 (model은 프론트엔드에서 전달)
                        executionService.executeSync(
                                        new com.example.springsupervisorai.model.SupervisorExecutionRequest(
                                                        sessionId, revisedMessage, null));
                        return Optional.of(Map.of(
                                        "status", "REVISED",
                                        "taskId", taskId,
                                        "revisedMessage", revisedMessage));
                }

                Optional<HitlReviewTicket> decided = hitlGateService.decide(taskId, sessionId, decisionType, reason,
                                decisionId, null);
                if (decided.isEmpty()) {
                        return Optional.empty();
                }
                HitlReviewTicket ticket = decided.get();
                if (decisionType == HitlDecisionType.CANCEL) {
                        taskFacade.cancelTask(taskId, sessionId, reason == null ? "Canceled by reviewer" : reason);
                        return buildDecisionResult(sessionId, taskId, ticket);
                }

                executionService.resumeApprovedTask(taskId,
                                new com.example.springsupervisorai.model.SupervisorExecutionRequest(
                                                sessionId,
                                                ticket.message(),
                                                ticket.model()));
                return buildDecisionResult(sessionId, taskId, ticket);
        }

        /**
         * review 결정을 반영하고, APPROVE인 경우 후속 supervisor 실행을 스트리밍한다.
         * <p>
         * CANCEL은 단건 결과만 있으면 충분하므로 progress + 최종 응답만 반환한다.
         *
         * @param sessionId  호출자 세션 id
         * @param taskId     task id
         * @param decision   결정 문자열(APPROVE/CANCEL)
         * @param reason     결정 사유
         * @param decisionId 결정 idempotency id
         * @return review 처리 + 후속 실행 이벤트 스트림
         */
        public Flux<SupervisorOutputEvent> decideReviewStream(
                        String sessionId,
                        String taskId,
                        String decision,
                        String reason,
                        String decisionId,
                        String revisedMessage) {
                HitlDecisionType decisionType = parseDecisionType(decision);
                if (decisionType == null) {
                        return Flux.empty();
                }

                if (decisionType == HitlDecisionType.REVISE) {
                        A2aTaskSnapshot task = taskFacade.getTask(taskId, sessionId)
                                        .orElseThrow(() -> new IllegalArgumentException("Task not found"));
                        // 사용자 입력 반영
                        taskFacade.updateTaskMessage(taskId, revisedMessage);
                        // HITL 티켓에 revisedMessage 반영
                        hitlGateService.decide(taskId, sessionId, HitlDecisionType.REVISE, "Revised by user",
                                        decisionId, revisedMessage);
                        // 재실행 스트리밍 (model은 프론트엔드에서 전달)
                        return executionService.executeStreamEvents(
                                        new com.example.springsupervisorai.model.SupervisorExecutionRequest(
                                                        sessionId, revisedMessage, null));
                }

                Optional<HitlReviewTicket> decided = hitlGateService.decide(taskId, sessionId, decisionType, reason,
                                decisionId, revisedMessage);
                if (decided.isEmpty()) {
                        return Flux.empty();
                }

                HitlReviewTicket ticket = decided.get();
                if (decisionType == HitlDecisionType.CANCEL) {
                        taskFacade.cancelTask(taskId, sessionId, reason == null ? "Canceled by reviewer" : reason);
                        return Flux.just(
                                        SupervisorOutputEvent.progress(SupervisorProgressSupport.event(
                                                        SupervisorProgressSupport.STAGE_HITL,
                                                        100,
                                                        "검토가 취소되어 요청이 종료되었습니다.",
                                                        Map.of(
                                                                        "taskId", taskId,
                                                                        "reviewStatus", ticket.status().name()))),
                                        SupervisorOutputEvent.text("요청이 검토 단계에서 취소되었습니다."));
                }

                return Flux.concat(
                                Flux.just(SupervisorOutputEvent.progress(SupervisorProgressSupport.event(
                                                SupervisorProgressSupport.STAGE_HITL,
                                                12,
                                                "승인이 완료되었습니다. 후속 실행을 시작합니다.",
                                                Map.of(
                                                                "taskId", taskId,
                                                                "reviewStatus", ticket.status().name())))),
                                executionService.resumeApprovedTaskStream(taskId,
                                                new com.example.springsupervisorai.model.SupervisorExecutionRequest(
                                                                sessionId,
                                                                ticket.message(),
                                                                ticket.model())));
        }

        /**
         * review 결정 결과(task/review)를 응답 포맷으로 조합한다.
         */
        private Optional<Map<String, Object>> buildDecisionResult(String sessionId, String taskId,
                        HitlReviewTicket ticket) {
                return taskFacade.getTask(taskId, sessionId)
                                .map(snapshot -> Map.<String, Object>of(
                                                "task", toTaskView(snapshot),
                                                "review", toTaskReviewView(ticket)));
        }

        private HitlDecisionType parseDecisionType(String decision) {
                if (decision == null || decision.isBlank()) {
                        return null;
                }
                return HitlDecisionType.from(decision.toUpperCase(Locale.ROOT)).orElse(null);
        }

        private TaskView toTaskView(A2aTaskSnapshot snapshot) {
                return responseMapper.toTaskView(snapshot);
        }

        private TaskReviewView toTaskReviewView(HitlReviewTicket ticket) {
                return responseMapper.toTaskReviewView(ticket);
        }
}
