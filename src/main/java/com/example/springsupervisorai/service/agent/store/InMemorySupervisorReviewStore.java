package com.example.springsupervisorai.service.agent.store;

import com.example.springsupervisorai.model.HitlDecisionType;
import com.example.springsupervisorai.model.HitlReviewStatus;
import com.example.springsupervisorai.model.HitlReviewTicket;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 메모리 기반 HITL review 저장소 구현.
 */
@Component
@ConditionalOnProperty(name = "app.redis.enabled", havingValue = "false", matchIfMissing = true)
public class InMemorySupervisorReviewStore implements SupervisorReviewStore {

    private final ConcurrentMap<String, HitlReviewTicket> reviews = new ConcurrentHashMap<>();

    /**
     * {@inheritDoc}
     */
    @Override
    public HitlReviewTicket open(HitlReviewTicket ticket) {
        reviews.put(ticket.taskId(), ticket);
        return ticket;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public HitlReviewTicket update(HitlReviewTicket ticket) {
        reviews.put(ticket.taskId(), ticket);
        return ticket;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Optional<HitlReviewTicket> get(String taskId) {
        HitlReviewTicket ticket = reviews.get(taskId);
        if (ticket == null || ticket.expiresAt().isBefore(Instant.now())) {
            if (ticket != null) {
                reviews.remove(taskId);
            }
            return Optional.empty();
        }
        return Optional.of(ticket);
    }

    /**
     * {@inheritDoc}
     * <p>
     * 원자적 `compute`로 WAITING -> APPROVED/CANCELED 전이를 보장한다.
     */
    /**
     * {@inheritDoc}
     */
    @Override
    public void evictExpired() {
        Instant now = Instant.now();
        reviews.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Optional<HitlReviewTicket> decide(String taskId, HitlDecisionType decision, String reason, String decisionId, String revisedMessage) {
        AtomicReference<HitlReviewTicket> updatedRef = new AtomicReference<>();
        reviews.compute(taskId, (key, current) -> {
            if (current == null) {
                return null;
            }
            if (current.expiresAt().isBefore(Instant.now())) {
                reviews.remove(taskId);
                return null;
            }
            if (!current.isWaiting()) {
                updatedRef.set(current);
                return current;
            }
            HitlReviewStatus status = decision == HitlDecisionType.APPROVE
                    ? HitlReviewStatus.APPROVED
                    : decision == HitlDecisionType.CANCEL
                        ? HitlReviewStatus.CANCELED
                        : HitlReviewStatus.REVISED;
            HitlReviewTicket updated = HitlReviewTicket.create(
                    current.taskId(),
                    current.sessionId(),
                    current.message(),
                    current.model(),
                    current.policyId(),
                    current.policyReason(),
                    status,
                    reason == null ? "" : reason,
                    current.requestedAt(),
                    current.expiresAt(),
                    Instant.now(),
                    decisionId == null ? "" : decisionId,
                    decision == HitlDecisionType.REVISE ? revisedMessage : current.revisedMessage()
            );
            updatedRef.set(updated);
            return updated;
        });
        return Optional.ofNullable(updatedRef.get());
    }
}
