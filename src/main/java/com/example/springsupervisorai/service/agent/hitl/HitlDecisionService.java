package com.example.springsupervisorai.service.agent.hitl;

import com.example.springsupervisorai.model.HitlDecisionType;
import com.example.springsupervisorai.model.HitlPolicyResult;
import com.example.springsupervisorai.model.HitlReviewTicket;

import java.util.Optional;

/**
 * HITL review 티켓의 생성/조회/결정 반영 포트.
 */
public interface HitlDecisionService {

    /**
     * 리뷰 티켓을 생성하고 대기 상태로 연다.
     *
     * @param taskId task id
     * @param sessionId 세션 id
     * @param message 사용자 메시지
     * @param model 모델 식별자
     * @param policyResult 정책 평가 결과
     * @return 생성된 리뷰 티켓
     */
    HitlReviewTicket openReview(String taskId, String sessionId, String message, String model, HitlPolicyResult policyResult);

    /**
     * 리뷰 티켓을 조회한다.
     *
     * @param taskId task id
     * @param sessionId 세션 id
     * @return 리뷰 티켓(optional)
     */
    Optional<HitlReviewTicket> getReview(String taskId, String sessionId);

    /**
     * 리뷰 결정(APPROVE/CANCEL)을 반영한다.
     *
     * @param taskId task id
     * @param sessionId 세션 id
     * @param decision 결정 타입
     * @param reason 결정 사유
     * @param decisionId 결정 idempotency id
     * @return 반영된 티켓(optional)
     */
    Optional<HitlReviewTicket> decide(String taskId, String sessionId, HitlDecisionType decision, String reason, String decisionId, String revisedMessage);
}
