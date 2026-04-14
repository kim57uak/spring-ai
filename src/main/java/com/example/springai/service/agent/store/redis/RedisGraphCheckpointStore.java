package com.example.springai.service.agent.store.redis;

import com.example.springai.common.redis.RedisKeyspace;
import com.example.springai.common.redis.RedisTtlPolicy;
import com.example.springai.service.agent.store.GraphCheckpointStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 그래프 체크포인트를 Redis에 저장하는 GraphCheckpointStore 구현체.
 * <p>
 * Redis 장애 시 로컬 메모리 폴백을 사용한다.
 */
@Component
public class RedisGraphCheckpointStore implements GraphCheckpointStore {

    private static final Logger logger = LoggerFactory.getLogger(RedisGraphCheckpointStore.class);
    private static final String KEY_PREFIX = RedisKeyspace.AGENT_CHECKPOINT_PREFIX;
    private static final java.time.Duration TTL = RedisTtlPolicy.STANDARD;

    private final StringRedisTemplate redisTemplate;
    private final RedisStoreSupport storeSupport;
    private final Map<String, String> localFallback = new ConcurrentHashMap<>();

    /**
     * @param redisTemplateProvider Redis 템플릿 제공자
     */
    public RedisGraphCheckpointStore(ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
        this.redisTemplate = redisTemplateProvider.getIfAvailable();
        this.storeSupport = new RedisStoreSupport(logger);
    }

    /**
     * Redis에서 체크포인트를 조회하고, 실패 시 로컬 폴백을 반환한다.
     *
     * @param sessionId 세션 식별자
     * @return 체크포인트 JSON 문자열
     */
    @Override
    public Optional<String> loadCheckpoint(String sessionId) {
        if (redisTemplate == null) {
            return Optional.ofNullable(localFallback.get(sessionId));
        }
        return storeSupport.runOrDefault(
                () -> {
                    String value = redisTemplate.opsForValue().get(key(sessionId));
                    if (value != null) {
                        localFallback.put(sessionId, value);
                    }
                    return Optional.ofNullable(value).or(() -> Optional.ofNullable(localFallback.get(sessionId)));
                },
                Optional.ofNullable(localFallback.get(sessionId)),
                "load checkpoint",
                sessionId
        );
    }

    /**
     * Redis와 로컬 폴백에 체크포인트를 함께 저장한다.
     *
     * @param sessionId 세션 식별자
     * @param payload   저장할 체크포인트
     */
    @Override
    public void saveCheckpoint(String sessionId, String payload) {
        if (payload != null) {
            localFallback.put(sessionId, payload);
        }
        if (redisTemplate == null) {
            return;
        }
        storeSupport.runSafely(() -> redisTemplate.opsForValue().set(key(sessionId), payload, TTL), "save checkpoint", sessionId);
    }

    /**
     * Redis/로컬 폴백의 체크포인트를 함께 삭제한다.
     *
     * @param sessionId 세션 식별자
     */
    @Override
    public void clear(String sessionId) {
        localFallback.remove(sessionId);
        if (redisTemplate == null) {
            return;
        }
        storeSupport.runSafely(() -> redisTemplate.delete(key(sessionId)), "clear checkpoint", sessionId);
    }

    private String key(String sessionId) {
        return KEY_PREFIX + sessionId;
    }
}
