package com.example.springai.a2a.task;

import com.example.springai.common.redis.RedisKeyspace;
import com.example.springai.common.redis.RedisTtlPolicy;
import com.example.springai.model.agent.AgentScopeName;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Redis 기반 A2A task 저장소.
 * <p>
 * - task 본문: key-value(JSON)
 * - scope별 목록: sorted-set(updatedAt epoch ms)
 */
@Component
@ConditionalOnProperty(name = "app.redis.enabled", havingValue = "true")
public class RedisA2ATaskStore implements A2ATaskStore {

    private static final Logger logger = LoggerFactory.getLogger(RedisA2ATaskStore.class);
    private static final String TASK_KEY_PREFIX = RedisKeyspace.AGENT_TASK_PREFIX;
    private static final String SCOPE_INDEX_PREFIX = RedisKeyspace.AGENT_TASK_SCOPE_INDEX_PREFIX;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Duration ttl;

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
     * 새로운 A2A task를 생성하고 저장한다.
     *
     * @param scopeName 스코프명
     * @param sessionId 세션 식별자
     * @param requestMessage 요청 본문
     * @return 생성된 task 스냅샷
     */
    @Override
    public A2aTaskSnapshot create(AgentScopeName scopeName, String sessionId, String requestMessage) {
        Instant now = Instant.now();
        String taskId = "task-" + UUID.randomUUID();
        A2aTaskSnapshot snapshot = new A2aTaskSnapshot(
                taskId,
                scopeName,
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
     * taskId + scopeName 기준 단건 task를 조회한다.
     *
     * @param taskId task 식별자
     * @param scopeName 스코프명
     * @return task 스냅샷
     */
    @Override
    public Optional<A2aTaskSnapshot> get(String taskId, AgentScopeName scopeName) {
        return load(taskId).filter(snapshot -> snapshot.scopeName() == scopeName);
    }

    /**
     * scope별 최신 task 목록을 조회한다.
     *
     * @param scopeName 스코프명
     * @param limit 반환 개수 상한(1~200으로 보정)
     * @return task 목록
     */
    @Override
    public List<A2aTaskSnapshot> list(AgentScopeName scopeName, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        String scopeIndexKey = scopeIndexKey(scopeName);
        Set<String> taskIds = redisTemplate.opsForZSet().reverseRange(scopeIndexKey, 0, safeLimit - 1);
        if (taskIds == null || taskIds.isEmpty()) {
            return List.of();
        }
        List<A2aTaskSnapshot> snapshots = new ArrayList<>();
        for (String taskId : taskIds) {
            Optional<A2aTaskSnapshot> loaded = load(taskId);
            if (loaded.isEmpty()) {
                redisTemplate.opsForZSet().remove(scopeIndexKey, taskId);
                continue;
            }
            A2aTaskSnapshot snapshot = loaded.get();
            if (snapshot.scopeName() != scopeName) {
                continue;
            }
            snapshots.add(snapshot);
        }
        return snapshots;
    }

    /**
     * task 상태를 RUNNING으로 전이한다.
     */
    @Override
    public Optional<A2aTaskSnapshot> markRunning(String taskId, AgentScopeName scopeName) {
        return update(taskId, scopeName, old -> A2aTaskSnapshotTransitions.markRunning(old, Instant.now()));
    }

    /**
     * task 상태를 COMPLETED로 전이한다.
     */
    @Override
    public Optional<A2aTaskSnapshot> markCompleted(String taskId, AgentScopeName scopeName, String responsePayload) {
        return update(taskId, scopeName, old -> A2aTaskSnapshotTransitions.markCompleted(old, responsePayload, Instant.now()));
    }

    /**
     * task 상태를 FAILED로 전이한다.
     */
    @Override
    public Optional<A2aTaskSnapshot> markFailed(String taskId, AgentScopeName scopeName, String errorCode, String errorMessage) {
        return update(taskId, scopeName, old -> A2aTaskSnapshotTransitions.markFailed(old, errorCode, errorMessage, Instant.now()));
    }

    /**
     * task 상태를 CANCELED로 전이한다.
     */
    @Override
    public Optional<A2aTaskSnapshot> cancel(String taskId, AgentScopeName scopeName, String reason) {
        return update(taskId, scopeName, old -> A2aTaskSnapshotTransitions.cancel(old, reason, Instant.now()));
    }

    /**
     * 현재 스냅샷을 조회한 뒤 updater를 적용하고 재저장한다.
     */
    private Optional<A2aTaskSnapshot> update(
            String taskId,
            AgentScopeName scopeName,
            Function<A2aTaskSnapshot, A2aTaskSnapshot> updater
    ) {
        Optional<A2aTaskSnapshot> current = load(taskId);
        if (current.isEmpty() || current.get().scopeName() != scopeName) {
            return Optional.empty();
        }
        A2aTaskSnapshot next = updater.apply(current.get());
        save(next);
        return Optional.of(next);
    }

    /**
     * task 본문과 scope 인덱스를 함께 저장한다.
     */
    private void save(A2aTaskSnapshot snapshot) {
        try {
            String key = taskKey(snapshot.taskId());
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(snapshot), ttl);
            redisTemplate.opsForZSet().add(scopeIndexKey(snapshot.scopeName()), snapshot.taskId(), snapshot.updatedAt().toEpochMilli());
            redisTemplate.expire(scopeIndexKey(snapshot.scopeName()), ttl);
        } catch (JsonProcessingException ex) {
            logger.error("Failed to serialize A2A task snapshot taskId={}", snapshot.taskId(), ex);
            throw new IllegalStateException("A2A task serialization failed", ex);
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
            logger.warn("Failed to deserialize A2A task snapshot taskId={}: {}", taskId, ex.getMessage());
            return Optional.empty();
        }
    }

    private String taskKey(String taskId) {
        return TASK_KEY_PREFIX + taskId;
    }

    private String scopeIndexKey(AgentScopeName scopeName) {
        return SCOPE_INDEX_PREFIX + scopeName.name();
    }
}
