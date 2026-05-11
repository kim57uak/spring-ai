package com.example.springsupervisorai.service.agent.store;

import com.example.springsupervisorai.model.HitlDecisionType;
import com.example.springsupervisorai.model.HitlReviewTicket;

import java.util.Optional;

/**
 * HITL review 티켓 저장소 포트.
 */
public interface SupervisorReviewStore {

    /**
     * 신규 리뷰 티켓을 저장한다.
     *
     * @param ticket 저장할 리뷰 티켓
     * @return 저장된 티켓
     */
    HitlReviewTicket open(HitlReviewTicket ticket);

    /**
     * taskId로 리뷰 티켓을 조회한다.
     *
     * @param taskId 조회할 task id
     * @return 리뷰 티켓(optional)
     */
    Optional<HitlReviewTicket> get(String taskId);

    /**
     * 리뷰 결정을 반영한다.
     *
     * @param taskId task id
     * @param decision 승인/취소 결정
     * @param reason 결정 사유
     * @param decisionId 결정 idempotency id
     * @return 결정 반영 후 티켓(optional)
     */
    Optional<HitlReviewTicket> decide(String taskId, HitlDecisionType decision, String reason, String decisionId, String revisedMessage);

    /**
     * REVISE 시 HITL 티켓을 갱신한다.
     *
     * @param ticket 갱신할 HITL 티켓
     * @return 갱신된 HITL 티켓
     */
    HitlReviewTicket update(HitlReviewTicket ticket);
}
