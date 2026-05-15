package com.example.springsupervisorai.service.agent.hitl;

import com.example.springsupervisorai.model.HitlDecisionType;
import com.example.springsupervisorai.model.HitlPolicyResult;
import com.example.springsupervisorai.model.HitlReviewStatus;
import com.example.springsupervisorai.model.HitlReviewTicket;
import com.example.springsupervisorai.model.SwarmState;
import com.example.springsupervisorai.service.agent.store.SupervisorReviewStore;
import com.example.springsupervisorai.service.agent.store.SupervisorSwarmStateStore;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 기본 HITL 결정 서비스 구현.
 * <p>
 * review 저장소와 swarm 상태 저장소를 동시에 갱신해
 * 승인/취소 이력을 추적 가능하게 유지한다.
 */
@Component
public class DefaultHitlDecisionService implements HitlDecisionService {

    private static final long REVIEW_TIMEOUT_SECONDS = 1_800;

    private final SupervisorReviewStore reviewStore;
    private final SupervisorSwarmStateStore swarmStateStore;

    public DefaultHitlDecisionService(
            SupervisorReviewStore reviewStore,
            SupervisorSwarmStateStore swarmStateStore
    ) {
        this.reviewStore = reviewStore;
        this.swarmStateStore = swarmStateStore;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public HitlReviewTicket openReview(String taskId, String sessionId, String message, String model, HitlPolicyResult policyResult) {
        Instant now = Instant.now();
        HitlReviewTicket ticket = new HitlReviewTicket(
                taskId,
                sessionId,
                message == null ? "" : message,
                model == null ? "" : model,
                policyResult.policyId(),
                policyResult.reason(),
                HitlReviewStatus.WAITING,
                "",
                now,
                now.plusSeconds(REVIEW_TIMEOUT_SECONDS),
                null,
                "",
                null
        );
        reviewStore.open(ticket);
        upsertSwarmState(taskId, sessionId, Map.of(
                "hitlRequired", true,
                "policyId", policyResult.policyId(),
                "policyReason", policyResult.reason()
        ), "HITL_REVIEW_OPENED");
        return ticket;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Optional<HitlReviewTicket> getReview(String taskId, String sessionId) {
        return reviewStore.get(taskId)
                .filter(ticket -> ticket.sessionId().equals(sessionId));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Optional<HitlReviewTicket> decide(String taskId, String sessionId, HitlDecisionType decision, String reason, String decisionId, String revisedMessage) {
        Optional<HitlReviewTicket> owned = getReview(taskId, sessionId);
        if (owned.isEmpty()) {
            return Optional.empty();
        }
        if (decision == HitlDecisionType.REVISE) {
            // REVISE 시 revisedMessage를 HITL 티켓에 반영
            HitlReviewTicket revisedTicket = new HitlReviewTicket(
                    owned.get().taskId(),
                    owned.get().sessionId(),
                    owned.get().message(),
                    owned.get().model(),
                    owned.get().policyId(),
                    owned.get().policyReason(),
                    HitlReviewStatus.REVISED,
                    "Revised by user",
                    owned.get().requestedAt(),
                    owned.get().expiresAt(),
                    Instant.now(),
                    owned.get().decisionId(),
                    revisedMessage
            );
            reviewStore.update(revisedTicket);
            upsertSwarmState(taskId, sessionId, Map.of(
                    "hitlDecision", "REVISED",
                    "decisionReason", "Revised by user"
            ), "HITL_REVIEW_REVISED");
            return Optional.of(revisedTicket);
        }
        Optional<HitlReviewTicket> decided = reviewStore.decide(taskId, decision, reason, decisionId, revisedMessage);
        decided.ifPresent(ticket -> upsertSwarmState(taskId, sessionId, Map.of(
                "hitlDecision", ticket.status().name(),
                "decisionReason", ticket.decisionReason()
        ), "HITL_REVIEW_DECIDED"));
        return decided;
    }

    /**
     * Swarm 상태를 버전 증가 방식으로 저장하고 이벤트 로그를 누적한다.
     *
     * @param taskId task id
     * @param sessionId 세션 id
     * @param facts 갱신할 shared facts
     * @param eventType 기록할 이벤트 타입
     */
    private void upsertSwarmState(String taskId, String sessionId, Map<String, Object> facts, String eventType) {
        SwarmState current = swarmStateStore.load(taskId).orElse(new SwarmState(
                taskId,
                sessionId,
                0L,
                Instant.now(),
                Map.of(),
                List.of()
        ));
        LinkedHashMap<String, Object> mergedFacts = new LinkedHashMap<>(current.sharedFacts());
        mergedFacts.putAll(facts);

        ArrayList<Map<String, Object>> events = new ArrayList<>(current.eventLog());
        events.add(Map.of(
                "type", eventType,
                "at", Instant.now().toString()
        ));
        swarmStateStore.upsert(new SwarmState(
                taskId,
                sessionId,
                current.stateVersion() + 1,
                Instant.now(),
                mergedFacts,
                events
        ));
    }
}
