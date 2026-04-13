package com.example.springsupervisorai.service.agent.store.redis;

import com.example.common.redis.RedisKeyspace;
import com.example.common.redis.RedisTtlPolicy;
import com.example.springsupervisorai.service.agent.store.GraphCheckpointStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

@Component("supervisorRedisGraphCheckpointStore")
public class RedisGraphCheckpointStore implements GraphCheckpointStore {

    private static final Logger logger = LoggerFactory.getLogger(RedisGraphCheckpointStore.class);
    private static final String KEY_PREFIX = RedisKeyspace.SUPERVISOR_CHECKPOINT_PREFIX;
    private static final java.time.Duration TTL = RedisTtlPolicy.STANDARD;

    private final StringRedisTemplate redisTemplate;
    private final Map<String, String> localFallback = new ConcurrentHashMap<>();

    public RedisGraphCheckpointStore(ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
        this.redisTemplate = redisTemplateProvider.getIfAvailable();
    }

    @Override
    public Optional<String> loadCheckpoint(String sessionId) {
        if (redisTemplate == null) {
            return Optional.ofNullable(localFallback.get(sessionId));
        }
        return runOrDefault(() -> {
            String value = redisTemplate.opsForValue().get(key(sessionId));
            if (value != null) {
                localFallback.put(sessionId, value);
            }
            return Optional.ofNullable(value).or(() -> Optional.ofNullable(localFallback.get(sessionId)));
        }, Optional.ofNullable(localFallback.get(sessionId)), "load checkpoint", sessionId);
    }

    @Override
    public void saveCheckpoint(String sessionId, String payload) {
        if (payload != null) {
            localFallback.put(sessionId, payload);
        }
        if (redisTemplate == null) {
            return;
        }
        runSafely(() -> redisTemplate.opsForValue().set(key(sessionId), payload, TTL), "save checkpoint", sessionId);
    }

    @Override
    public void clear(String sessionId) {
        localFallback.remove(sessionId);
        if (redisTemplate == null) {
            return;
        }
        runSafely(() -> redisTemplate.delete(key(sessionId)), "clear checkpoint", sessionId);
    }

    private String key(String sessionId) {
        return KEY_PREFIX + sessionId;
    }

    private <T> T runOrDefault(Supplier<T> action, T fallback, String actionName, String sessionId) {
        try {
            return action.get();
        } catch (Exception ex) {
            logger.warn("Failed to {} in Redis for session {}: {}", actionName, sessionId, ex.getMessage());
            return fallback;
        }
    }

    private void runSafely(Runnable action, String actionName, String sessionId) {
        try {
            action.run();
        } catch (Exception ex) {
            logger.warn("Failed to {} in Redis for session {}: {}", actionName, sessionId, ex.getMessage());
        }
    }
}
