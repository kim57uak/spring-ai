package com.example.springsupervisorai.service.agent.store;

import com.example.springsupervisorai.common.redis.RedisKeyspace;
import com.example.springsupervisorai.common.redis.RedisTtlPolicy;
import com.example.springsupervisorai.model.HitlDecisionType;
import com.example.springsupervisorai.model.HitlReviewStatus;
import com.example.springsupervisorai.model.HitlReviewTicket;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Redis 기반 HITL review 저장소.
 */
@Component
@ConditionalOnProperty(name = "app.redis.enabled", havingValue = "true")
public class RedisSupervisorReviewStore implements SupervisorReviewStore {

    private static final Logger logger = LoggerFactory.getLogger(RedisSupervisorReviewStore.class);
    // review 조회/결정 API가 taskId를 기준으로 동작하므로 키도 taskId 기반으로 유지한다.
    private static final String KEY_PREFIX = RedisKeyspace.SUPERVISOR_REVIEW_PREFIX;
    // 요청하신 운영 기준: review TTL 30분 고정
    private static final java.time.Duration TTL = RedisTtlPolicy.STANDARD;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisSupervisorReviewStore(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public HitlReviewTicket open(HitlReviewTicket ticket) {
        save(ticket);
        return ticket;
    }

    @Override
    public Optional<HitlReviewTicket> get(String taskId) {
        String raw = redisTemplate.opsForValue().get(key(taskId));
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        return deserialize(raw, taskId);
    }

    @Override
    public Optional<HitlReviewTicket> decide(String taskId, HitlDecisionType decision, String reason, String decisionId, String revisedMessage) {
        String key = key(taskId);
        HitlReviewTicket decided = redisTemplate.execute(new SessionCallback<>() {
            @Override
            @SuppressWarnings("unchecked")
            public HitlReviewTicket execute(RedisOperations operations) {
                operations.watch(key);
                Object rawValue = operations.opsForValue().get(key);
                String raw = rawValue == null ? "" : String.valueOf(rawValue);
                if (raw.isBlank()) {
                    operations.unwatch();
                    return null;
                }
                Optional<HitlReviewTicket> current = deserialize(raw, taskId);
                if (current.isEmpty()) {
                    operations.unwatch();
                    return null;
                }
                if (!current.get().isWaiting()) {
                    operations.unwatch();
                    return current.get();
                }

                HitlReviewStatus status = decision == HitlDecisionType.APPROVE
                        ? HitlReviewStatus.APPROVED
                        : HitlReviewStatus.CANCELED;
                HitlReviewTicket updated = HitlReviewTicket.create(
                        current.get().taskId(),
                        current.get().sessionId(),
                        current.get().message(),
                        current.get().model(),
                        current.get().policyId(),
                        current.get().policyReason(),
                        status,
                        reason == null ? "" : reason,
                        current.get().requestedAt(),
                        current.get().expiresAt(),
                        Instant.now(),
                        decisionId == null ? "" : decisionId,
                        decision == HitlDecisionType.REVISE ? revisedMessage : current.get().revisedMessage()
                );
                operations.multi();
                operations.opsForValue().set(key, serialize(updated), TTL);
                List<Object> exec = operations.exec();
                if (exec == null) {
                    return null;
                }
                return updated;
            }
        });
        return Optional.ofNullable(decided);
    }

    private void save(HitlReviewTicket ticket) {
        redisTemplate.opsForValue().set(key(ticket.taskId()), serialize(ticket), TTL);
    }

    private String key(String taskId) {
        return KEY_PREFIX + taskId;
    }

    private String serialize(HitlReviewTicket ticket) {
        try {
            return objectMapper.writeValueAsString(ticket);
        } catch (JsonProcessingException ex) {
            logger.error("Failed to serialize review ticket taskId={}", ticket.taskId(), ex);
            throw new IllegalStateException("Review ticket serialization failed", ex);
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * Redis는 TTL 기반 자동 만료를 사용하므로 별도 처리하지 않는다.
     */
    @Override
    public void evictExpired() {
        // Redis TTL handles expiry automatically
    }

    @Override
    public HitlReviewTicket update(HitlReviewTicket ticket) {
        save(ticket);
        return ticket;
    }

    private Optional<HitlReviewTicket> deserialize(String raw, String taskId) {
        try {
            return Optional.of(objectMapper.readValue(raw, HitlReviewTicket.class));
        } catch (Exception ex) {
            logger.warn("Failed to deserialize review ticket taskId={}: {}", taskId, ex.getMessage());
            return Optional.empty();
        }
    }
}
