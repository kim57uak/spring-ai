package com.example.springsupervisorai.a2a.task;

import com.example.common.redis.RedisKeyspace;
import com.example.common.redis.RedisTtlPolicy;
import com.example.springsupervisorai.model.SupervisorErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Supervisor task Redis 저장소.
 * <p>
 * - task 본문: key-value(JSON)
 * - 목록 인덱스: sorted-set(updatedAt epoch ms)
 */
@Component("supervisorRedisA2ATaskStore")
@ConditionalOnProperty(name = "app.redis.enabled", havingValue = "true")
public class RedisA2ATaskStore implements A2ATaskStore {

    private static final Logger logger = LoggerFactory.getLogger(RedisA2ATaskStore.class);
    // 요청하신 운영 기준: task 데이터 TTL 30분 고정
    private static final java.time.Duration TTL = RedisTtlPolicy.STANDARD;
    // 단건 조회(get)는 taskId 기반 계약이라 본문 키는 taskId를 사용한다.
    private static final String TASK_KEY_PREFIX = RedisKeyspace.SUPERVISOR_TASK_PREFIX;
    // 목록(list)은 최신순 조회를 위해 전역 sorted-set 인덱스를 사용한다.
    private static final String TASK_INDEX_KEY = RedisKeyspace.SUPERVISOR_TASK_INDEX_KEY;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisA2ATaskStore(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public A2aTaskSnapshot create(String sessionId, String requestMessage) {
        Instant now = Instant.now();
        String taskId = "sup-task-" + UUID.randomUUID();
        A2aTaskSnapshot snapshot = new A2aTaskSnapshot(
                taskId,
                sessionId,
                A2aTaskStatus.SUBMITTED,
                now,
                now,
                requestMessage == null ? "" : requestMessage,
                "",
                "",
                ""
        );
        save(snapshot);
        return snapshot;
    }

    @Override
    public Optional<A2aTaskSnapshot> get(String taskId) {
        return load(taskId);
    }

    @Override
    public List<A2aTaskSnapshot> list(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        Set<String> taskIds = redisTemplate.opsForZSet().reverseRange(TASK_INDEX_KEY, 0, safeLimit - 1);
        if (taskIds == null || taskIds.isEmpty()) {
            return List.of();
        }
        List<A2aTaskSnapshot> snapshots = new ArrayList<>();
        for (String taskId : taskIds) {
            Optional<A2aTaskSnapshot> loaded = load(taskId);
            if (loaded.isEmpty()) {
                redisTemplate.opsForZSet().remove(TASK_INDEX_KEY, taskId);
                continue;
            }
            snapshots.add(loaded.get());
        }
        return snapshots;
    }

    @Override
    public Optional<A2aTaskSnapshot> markRunning(String taskId) {
        return update(taskId, old -> new A2aTaskSnapshot(
                old.taskId(),
                old.sessionId(),
                A2aTaskStatus.RUNNING,
                old.createdAt(),
                Instant.now(),
                old.requestMessage(),
                old.responsePayload(),
                old.errorCode(),
                old.errorMessage()
        ));
    }

    @Override
    public Optional<A2aTaskSnapshot> markWaitingReview(String taskId, String reason) {
        return update(taskId, old -> new A2aTaskSnapshot(
                old.taskId(),
                old.sessionId(),
                A2aTaskStatus.WAITING_REVIEW,
                old.createdAt(),
                Instant.now(),
                old.requestMessage(),
                old.responsePayload(),
                "HITL_REQUIRED",
                reason == null ? "Human review is required" : reason
        ));
    }

    @Override
    public Optional<A2aTaskSnapshot> markCompleted(String taskId, String responsePayload) {
        return update(taskId, old -> {
            if (old.status() == A2aTaskStatus.CANCELED) {
                return old;
            }
            return new A2aTaskSnapshot(
                    old.taskId(),
                    old.sessionId(),
                    A2aTaskStatus.COMPLETED,
                    old.createdAt(),
                    Instant.now(),
                    old.requestMessage(),
                    responsePayload == null ? "" : responsePayload,
                    "",
                    ""
            );
        });
    }

    @Override
    public Optional<A2aTaskSnapshot> markFailed(String taskId, String errorCode, String errorMessage) {
        return update(taskId, old -> {
            if (old.status() == A2aTaskStatus.CANCELED) {
                return old;
            }
            return new A2aTaskSnapshot(
                    old.taskId(),
                    old.sessionId(),
                    A2aTaskStatus.FAILED,
                    old.createdAt(),
                    Instant.now(),
                    old.requestMessage(),
                    old.responsePayload(),
                    errorCode == null ? SupervisorErrorCode.INTERNAL_ERROR.value() : errorCode,
                    errorMessage == null ? "Supervisor task failed" : errorMessage
            );
        });
    }

    @Override
    public Optional<A2aTaskSnapshot> cancel(String taskId, String reason) {
        return update(taskId, old -> new A2aTaskSnapshot(
                old.taskId(),
                old.sessionId(),
                A2aTaskStatus.CANCELED,
                old.createdAt(),
                Instant.now(),
                old.requestMessage(),
                old.responsePayload(),
                SupervisorErrorCode.CANCELED.value(),
                reason == null || reason.isBlank() ? "Canceled by request" : reason
        ));
    }

    private Optional<A2aTaskSnapshot> update(String taskId, Function<A2aTaskSnapshot, A2aTaskSnapshot> updater) {
        Optional<A2aTaskSnapshot> current = load(taskId);
        if (current.isEmpty()) {
            return Optional.empty();
        }
        A2aTaskSnapshot next = updater.apply(current.get());
        save(next);
        return Optional.of(next);
    }

    private void save(A2aTaskSnapshot snapshot) {
        try {
            String key = taskKey(snapshot.taskId());
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(snapshot), TTL);
            redisTemplate.opsForZSet().add(TASK_INDEX_KEY, snapshot.taskId(), snapshot.updatedAt().toEpochMilli());
            redisTemplate.expire(TASK_INDEX_KEY, TTL);
        } catch (JsonProcessingException ex) {
            logger.error("Failed to serialize supervisor task snapshot taskId={}", snapshot.taskId(), ex);
            throw new IllegalStateException("Supervisor task serialization failed", ex);
        }
    }

    private Optional<A2aTaskSnapshot> load(String taskId) {
        String raw = redisTemplate.opsForValue().get(taskKey(taskId));
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(raw, A2aTaskSnapshot.class));
        } catch (Exception ex) {
            logger.warn("Failed to deserialize supervisor task snapshot taskId={}: {}", taskId, ex.getMessage());
            return Optional.empty();
        }
    }

    private String taskKey(String taskId) {
        return TASK_KEY_PREFIX + taskId;
    }
}
