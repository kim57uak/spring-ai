package com.example.springai.service.agent.store.redis;

import com.example.springai.service.agent.store.GraphCheckpointStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;

@Component
public class RedisGraphCheckpointStore implements GraphCheckpointStore {

    private static final Logger logger = LoggerFactory.getLogger(RedisGraphCheckpointStore.class);
    private static final String KEY_PREFIX = "agent:ckpt:";
    private static final Duration TTL = Duration.ofMinutes(30);

    private final StringRedisTemplate redisTemplate;

    public RedisGraphCheckpointStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Optional<String> loadCheckpoint(String sessionId) {
        return runOrDefault(
                () -> Optional.ofNullable(redisTemplate.opsForValue().get(key(sessionId))),
                Optional.empty(),
                "load checkpoint",
                sessionId
        );
    }

    @Override
    public void saveCheckpoint(String sessionId, String payload) {
        runSafely(() -> redisTemplate.opsForValue().set(key(sessionId), payload, TTL), "save checkpoint", sessionId);
    }

    @Override
    public void clear(String sessionId) {
        runSafely(() -> redisTemplate.delete(key(sessionId)), "clear checkpoint", sessionId);
    }

    private String key(String sessionId) {
        return KEY_PREFIX + sessionId;
    }

    private <T> T runOrDefault(Supplier<T> action, T defaultValue, String actionName, String sessionId) {
        try {
            return action.get();
        } catch (Exception e) {
            logger.warn("Failed to {} in Redis for session {}: {}", actionName, sessionId, e.getMessage());
            return defaultValue;
        }
    }

    private void runSafely(Runnable action, String actionName, String sessionId) {
        try {
            action.run();
        } catch (Exception e) {
            logger.warn("Failed to {} in Redis for session {}: {}", actionName, sessionId, e.getMessage());
        }
    }
}
