package com.example.springai.service.agent.store.redis;

import com.example.springai.service.agent.store.GraphCheckpointStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

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
        try {
            return Optional.ofNullable(redisTemplate.opsForValue().get(key(sessionId)));
        } catch (Exception e) {
            logger.warn("Failed to load checkpoint from Redis for session {}: {}", sessionId, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void saveCheckpoint(String sessionId, String payload) {
        try {
            redisTemplate.opsForValue().set(key(sessionId), payload, TTL);
        } catch (Exception e) {
            logger.warn("Failed to save checkpoint to Redis for session {}: {}", sessionId, e.getMessage());
        }
    }

    @Override
    public void clear(String sessionId) {
        try {
            redisTemplate.delete(key(sessionId));
        } catch (Exception e) {
            logger.warn("Failed to clear checkpoint from Redis for session {}: {}", sessionId, e.getMessage());
        }
    }

    private String key(String sessionId) {
        return KEY_PREFIX + sessionId;
    }
}
