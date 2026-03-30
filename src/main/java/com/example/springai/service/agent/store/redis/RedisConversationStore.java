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
        return runOrDefault(() -> {
            String raw = redisTemplate.opsForValue().get(key(sessionId));
            if (raw == null || raw.isBlank()) {
                return List.of();
            }
            return objectMapper.readValue(raw, new TypeReference<List<String>>() {});
        }, List.of(), "load conversation", sessionId);
    }

    @Override
    public void save(String sessionId, List<String> messages) {
        List<String> safeMessages = messages == null ? Collections.emptyList() : messages;
        runSafely(() -> {
            String payload = objectMapper.writeValueAsString(safeMessages);
            redisTemplate.opsForValue().set(key(sessionId), payload, TTL);
        }, "save conversation", sessionId);
    }

    @Override
    public void clear(String sessionId) {
        runSafely(() -> redisTemplate.delete(key(sessionId)), "clear conversation", sessionId);
    }

    private String key(String sessionId) {
        return KEY_PREFIX + sessionId;
    }

    private <T> T runOrDefault(ThrowingSupplier<T> action, T defaultValue, String actionName, String sessionId) {
        try {
            return action.get();
        } catch (Exception e) {
            logger.warn("Failed to {} in Redis for session {}: {}", actionName, sessionId, e.getMessage());
            return defaultValue;
        }
    }

    private void runSafely(ThrowingRunnable action, String actionName, String sessionId) {
        try {
            action.run();
        } catch (Exception e) {
            logger.warn("Failed to {} in Redis for session {}: {}", actionName, sessionId, e.getMessage());
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
