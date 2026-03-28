package com.example.springai.service.agent.store.redis;

import com.example.springai.service.agent.store.ConversationStore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.List;

@Component
public class RedisConversationStore implements ConversationStore {

    private static final Logger logger = LoggerFactory.getLogger(RedisConversationStore.class);
    private static final String KEY_PREFIX = "agent:conv:";
    private static final Duration TTL = Duration.ofMinutes(30);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisConversationStore(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<String> load(String sessionId) {
        try {
            String raw = redisTemplate.opsForValue().get(key(sessionId));
            if (raw == null || raw.isBlank()) {
                return List.of();
            }
            return objectMapper.readValue(raw, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            logger.warn("Failed to load conversation from Redis for session {}: {}", sessionId, e.getMessage());
            return List.of();
        }
    }

    @Override
    public void save(String sessionId, List<String> messages) {
        List<String> safeMessages = messages == null ? Collections.emptyList() : messages;
        try {
            String payload = objectMapper.writeValueAsString(safeMessages);
            redisTemplate.opsForValue().set(key(sessionId), payload, TTL);
        } catch (Exception e) {
            logger.warn("Failed to save conversation to Redis for session {}: {}", sessionId, e.getMessage());
        }
    }

    @Override
    public void clear(String sessionId) {
        try {
            redisTemplate.delete(key(sessionId));
        } catch (Exception e) {
            logger.warn("Failed to clear conversation from Redis for session {}: {}", sessionId, e.getMessage());
        }
    }

    private String key(String sessionId) {
        return KEY_PREFIX + sessionId;
    }
}
