package com.example.springsupervisorai.service;

import com.example.springsupervisorai.a2a.task.A2aTaskSnapshot;
import com.example.springsupervisorai.model.HitlDecisionType;
import com.example.springsupervisorai.model.HitlPolicyContext;
import com.example.springsupervisorai.model.HitlPolicyResult;
import com.example.springsupervisorai.model.HitlReviewTicket;
import com.example.springsupervisorai.service.agent.hitl.HitlDecisionService;
import com.example.springsupervisorai.service.agent.hitl.HitlPolicyService;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * HITL 평가와 review open/decide 흐름을 캡슐화하는 서비스.
 */
@Service
public class HitlGateService {

    private final HitlPolicyService hitlPolicyService;
    private final HitlDecisionService hitlDecisionService;
    private final SupervisorTaskFacade taskFacade;

    public HitlGateService(
            HitlPolicyService hitlPolicyService,
            HitlDecisionService hitlDecisionService,
            SupervisorTaskFacade taskFacade
    ) {
        this.hitlPolicyService = hitlPolicyService;
        this.hitlDecisionService = hitlDecisionService;
        this.taskFacade = taskFacade;
    }

    /**
     * HITL 정책을 typed context 기준으로 평가한다.
     */
    public HitlPolicyResult evaluate(String sessionId, String message, String model) {
        return hitlPolicyService.evaluate(HitlPolicyContext.of(sessionId, message, model));
    }

    /**
     * HITL review 대기 task를 만들고 review 티켓을 연다.
     *
     * @return 생성된 waiting task
     */
    public A2aTaskSnapshot openReview(String sessionId, String message, String model, HitlPolicyResult policyResult) {
        A2aTaskSnapshot waitingTask = taskFacade.createWaitingReviewTask(sessionId, message, policyResult.reason());
        hitlDecisionService.openReview(waitingTask.taskId(), sessionId, message, model, policyResult);
        return waitingTask;
    }

    public Optional<HitlReviewTicket> getReview(String taskId, String sessionId) {
        return hitlDecisionService.getReview(taskId, sessionId);
    }

    public Optional<HitlReviewTicket> decide(
            String taskId,
            String sessionId,
            HitlDecisionType decision,
            String reason,
            String decisionId
    ) {
        return hitlDecisionService.decide(taskId, sessionId, decision, reason, decisionId);
    }
}
