package com.example.springsupervisorai.service.agent.store;

import com.example.springsupervisorai.common.redis.RedisKeyspace;
import com.example.springsupervisorai.common.redis.RedisTtlPolicy;
import com.example.springsupervisorai.model.SwarmState;
import com.example.springsupervisorai.service.agent.swarm.SwarmStateVersionConflictException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Redis 기반 Swarm state 저장소 구현.
 * <p>
 * 분산 환경 안전성:
 * - Redis WATCH/MULTI/EXEC를 통한 트랜잭션 기반 낙관적 락 구현
 * - 여러 인스턴스에서 동시 접근 시에도 stateVersion 충돌 감지
 * - TTL 자동 설정으로 메모리 누수 방지 (기본 1시간)
 * <p>
 * 활성화 조건:
 * - app.redis.enabled=true 설정 시
 * - Redis 연결 정보 필수: spring.data.redis.host, port 등
 * <p>
 * 성능 고려사항:
 * - sessionId 기반 조회는 별도 인덱스 키 사용 (swarm:session:{sessionId})
 * - 모든 작업에 TTL 갱신하여 active session 유지
 */
@Component
@ConditionalOnProperty(name = "app.redis.enabled", havingValue = "true")
public class RedisSupervisorSwarmStateStore implements SupervisorSwarmStateStore {

    private static final Logger logger = LoggerFactory.getLogger(RedisSupervisorSwarmStateStore.class);
    private static final String KEY_PREFIX = RedisKeyspace.SWARM_STATE_PREFIX;
    private static final String SESSION_INDEX_PREFIX = RedisKeyspace.SWARM_SESSION_INDEX_PREFIX;

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final Duration ttl;

    public RedisSupervisorSwarmStateStore(
            RedisTemplate<String, String> redisTemplate,
            ObjectMapper objectMapper,
            RedisTtlPolicy ttlPolicy
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.ttl = ttlPolicy.getSwarmState();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Optional<SwarmState> load(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return Optional.empty();
        }
        String key = KEY_PREFIX + taskId;
        String json = redisTemplate.opsForValue().get(key);
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        return deserialize(json);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Optional<SwarmState> loadLatestBySession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        String indexKey = SESSION_INDEX_PREFIX + sessionId;
        String taskId = redisTemplate.opsForValue().get(indexKey);
        if (taskId == null || taskId.isBlank()) {
            return Optional.empty();
        }
        return load(taskId);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void clearSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        String indexKey = SESSION_INDEX_PREFIX + sessionId;
        String taskId = redisTemplate.opsForValue().get(indexKey);
        redisTemplate.delete(indexKey);
        if (taskId != null && !taskId.isBlank()) {
            redisTemplate.delete(KEY_PREFIX + taskId);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public SwarmState upsert(SwarmState state) throws SwarmStateVersionConflictException {
        String taskId = state.taskId();
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId cannot be null or blank");
        }

        String key = KEY_PREFIX + taskId;
        String json = serialize(state);
        String sessionId = state.sessionId();
        String indexKey = (sessionId == null || sessionId.isBlank()) ? null : SESSION_INDEX_PREFIX + sessionId;

        SwarmState saved = redisTemplate.execute(new SessionCallback<>() {
            @Override
            @SuppressWarnings("unchecked")
            public SwarmState execute(RedisOperations operations) {
                /*
                 * WATCH + MULTI + EXEC 기반 CAS:
                 * 1) state key를 WATCH하고 현재 버전 확인
                 * 2) 버전 일치 시에만 MULTI/EXEC로 저장
                 * 3) 중간에 타 writer가 수정하면 EXEC=null(충돌)로 실패
                 */
                operations.watch(key);
                Object rawCurrentJson = operations.opsForValue().get(key);
                String currentJson = rawCurrentJson == null ? "" : String.valueOf(rawCurrentJson);
                Optional<SwarmState> current = currentJson.isBlank() ? Optional.empty() : deserialize(currentJson);
                if (!currentJson.isBlank() && current.isEmpty()) {
                    operations.unwatch();
                    throw new IllegalStateException("Stored SwarmState is malformed for taskId=" + taskId);
                }

                if (state.stateVersion() > 0 && current.isPresent()) {
                    long expectedPreviousVersion = state.stateVersion() - 1;
                    long currentVersion = current.get().stateVersion();
                    if (currentVersion != expectedPreviousVersion) {
                        operations.unwatch();
                        throw new SwarmStateVersionConflictException(taskId, expectedPreviousVersion, currentVersion);
                    }
                }

                operations.multi();
                operations.opsForValue().set(key, json, ttl);
                if (indexKey != null) {
                    operations.opsForValue().set(indexKey, taskId, ttl);
                }
                List<Object> execResult = operations.exec();

                if (execResult == null) {
                    // WATCH 충돌: 최신 버전을 다시 읽어 충돌 정보를 포함해 예외를 올린다.
                    Optional<SwarmState> latest = load(taskId);
                    long expectedPreviousVersion = state.stateVersion() - 1;
                    long actualVersion = latest.map(SwarmState::stateVersion).orElse(-1L);
                    throw new SwarmStateVersionConflictException(taskId, expectedPreviousVersion, actualVersion);
                }
                return state;
            }
        });
        if (saved == null) {
            throw new IllegalStateException("Redis transaction returned null for taskId=" + taskId);
        }

        logger.debug("SwarmState upserted to Redis: taskId={}, sessionId={}, version={}",
                taskId, state.sessionId(), state.stateVersion());

        return saved;
    }

    /**
     * SwarmState 객체를 JSON 문자열로 직렬화한다.
     *
     * @param state 직렬화 대상
     * @return JSON 문자열
     */
    private String serialize(SwarmState state) {
        try {
            return objectMapper.writeValueAsString(state);
        } catch (JsonProcessingException e) {
            logger.error("Failed to serialize SwarmState: taskId={}", state.taskId(), e);
            throw new IllegalStateException("SwarmState serialization failed", e);
        }
    }

    /**
     * JSON 문자열을 SwarmState 객체로 역직렬화한다.
     *
     * @param json JSON 문자열
     * @return SwarmState 객체
     */
    private Optional<SwarmState> deserialize(String json) {
        try {
            SwarmState state = objectMapper.readValue(json, SwarmState.class);
            return Optional.of(state);
        } catch (JsonProcessingException e) {
            logger.error("Failed to deserialize SwarmState: json={}", json, e);
            return Optional.empty();
        }
    }
}
