package com.example.springsupervisorai.a2a.task;

import com.example.springsupervisorai.common.redis.RedisKeyspace;
import com.example.springsupervisorai.common.redis.RedisTtlPolicy;
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
    private static final String TASK_KEY_PREFIX = RedisKeyspace.SUPERVISOR_TASK_PREFIX;
    private static final String TASK_INDEX_KEY = RedisKeyspace.SUPERVISOR_TASK_INDEX_KEY;
    private static final String SESSION_TASKS_PREFIX = RedisKeyspace.SUPERVISOR_TASK_SESSION_PREFIX;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final java.time.Duration ttl;

    /**
     * @param redisTemplate Redis 접근 템플릿
     * @param objectMapper  스냅샷 직렬화 도구
     * @param ttlPolicy     TTL 정책
     */
    public RedisA2ATaskStore(StringRedisTemplate redisTemplate, ObjectMapper objectMapper, RedisTtlPolicy ttlPolicy) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.ttl = ttlPolicy.getStandard();
    }

    /**
     * Supervisor task를 생성하고 저장한다.
     *
     * @param sessionId 세션 식별자
     * @param requestMessage 요청 메시지
     * @return 생성된 task 스냅샷
     */
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

    /**
     * taskId 기준 단건 task를 조회한다.
     */
    @Override
    public Optional<A2aTaskSnapshot> get(String taskId) {
        return load(taskId);
    }

    /**
     * 최신순 task 목록을 조회한다.
     *
     * @param limit 반환 개수 상한(1~200으로 보정)
     * @return task 목록
     */
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

    /**
     * task 상태를 RUNNING으로 전이한다.
     */
    @Override
    public Optional<A2aTaskSnapshot> markRunning(String taskId) {
        return update(taskId, old -> A2aTaskSnapshotTransitions.markRunning(old, Instant.now()));
    }

    /**
     * task 상태를 WAITING_REVIEW로 전이한다.
     */
    @Override
    public Optional<A2aTaskSnapshot> markWaitingReview(String taskId, String reason) {
        return update(taskId, old -> A2aTaskSnapshotTransitions.markWaitingReview(old, reason, Instant.now()));
    }

    /**
     * task 상태를 COMPLETED로 전이한다.
     */
    @Override
    public Optional<A2aTaskSnapshot> markCompleted(String taskId, String responsePayload) {
        return update(taskId, old -> A2aTaskSnapshotTransitions.markCompleted(old, responsePayload, Instant.now()));
    }

    /**
     * task 상태를 FAILED로 전이한다.
     */
    @Override
    public Optional<A2aTaskSnapshot> markFailed(String taskId, String errorCode, String errorMessage) {
        return update(taskId, old -> A2aTaskSnapshotTransitions.markFailed(old, errorCode, errorMessage, Instant.now()));
    }

    /**
     * task 상태를 CANCELED로 전이한다.
     */
    @Override
    public Optional<A2aTaskSnapshot> cancel(String taskId, String reason) {
        return update(taskId, old -> A2aTaskSnapshotTransitions.cancel(old, reason, Instant.now()));
    }

    /**
     * task의 요청 메시지를 갱신한다.
     */
    @Override
    public Optional<A2aTaskSnapshot> updateTaskMessage(String taskId, String newMessage) {
        return update(taskId, old -> A2aTaskSnapshotTransitions.updateTaskMessage(old, newMessage, Instant.now()));
    }

    /**
     * 현재 스냅샷을 조회한 뒤 updater를 적용하고 재저장한다.
     */
    private Optional<A2aTaskSnapshot> update(String taskId, Function<A2aTaskSnapshot, A2aTaskSnapshot> updater) {
        Optional<A2aTaskSnapshot> current = load(taskId);
        if (current.isEmpty()) {
            return Optional.empty();
        }
        A2aTaskSnapshot next = updater.apply(current.get());
        save(next);
        return Optional.of(next);
    }

    /**
     * task 본문, 전역 인덱스, 세션 인덱스를 함께 저장한다.
     */
    private void save(A2aTaskSnapshot snapshot) {
        try {
            String key = taskKey(snapshot.taskId());
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(snapshot), ttl);
            redisTemplate.opsForZSet().add(TASK_INDEX_KEY, snapshot.taskId(), snapshot.updatedAt().toEpochMilli());
            redisTemplate.expire(TASK_INDEX_KEY, ttl);
            String sessionKey = sessionTasksKey(snapshot.sessionId());
            redisTemplate.opsForSet().add(sessionKey, snapshot.taskId());
            redisTemplate.expire(sessionKey, ttl);
        } catch (JsonProcessingException ex) {
            logger.error("Failed to serialize supervisor task snapshot taskId={}", snapshot.taskId(), ex);
            throw new IllegalStateException("Supervisor task serialization failed", ex);
        }
    }

    /**
     * taskId 기준 단건 스냅샷을 조회한다.
     */
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

    @Override
    public void clearSession(String sessionId) {
        String sessionKey = sessionTasksKey(sessionId);
        Set<String> indexedTaskIds = redisTemplate.opsForSet().members(sessionKey);
        // Delete tasks found via session index (fast path)
        if (indexedTaskIds != null) {
            for (String taskId : indexedTaskIds) {
                redisTemplate.delete(taskKey(taskId));
                redisTemplate.opsForZSet().remove(TASK_INDEX_KEY, taskId);
            }
        }
        // Scan global index for tasks of this session not yet in session index
        // (handles data created before session index was introduced)
        Set<String> allTaskIds = redisTemplate.opsForZSet().range(TASK_INDEX_KEY, 0, -1);
        if (allTaskIds != null) {
            for (String taskId : allTaskIds) {
                if (indexedTaskIds != null && indexedTaskIds.contains(taskId)) continue;
                load(taskId).ifPresent(snapshot -> {
                    if (sessionId.equals(snapshot.sessionId())) {
                        redisTemplate.delete(taskKey(taskId));
                        redisTemplate.opsForZSet().remove(TASK_INDEX_KEY, taskId);
                    }
                });
            }
        }
        redisTemplate.delete(sessionKey);
    }

    private String taskKey(String taskId) {
        return TASK_KEY_PREFIX + taskId;
    }

    private String sessionTasksKey(String sessionId) {
        return SESSION_TASKS_PREFIX + sessionId;
    }
}
