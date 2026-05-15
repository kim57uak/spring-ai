package com.example.springsupervisorai.service.agent.hitl;

import com.example.springsupervisorai.model.HitlDecisionType;
import com.example.springsupervisorai.model.HitlPolicyResult;
import com.example.springsupervisorai.model.HitlReviewTicket;

import java.util.Optional;

/**
 * HITL 리뷰 티켓 관리 포트: HITL 리뷰를 열고, 조회하고, 결정한다.
 * <p>
 * Supervisor 그래프 실행 중 HITL 게이트는 진행 전 수동 승인이 필요한
 * 리뷰 티켓을 생성할 수 있다. 이 포트는 티켓 영속화 및 라이프사이클 관리를 추상화한다.
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
