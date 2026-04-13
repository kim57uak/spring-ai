package com.example.springai.service.agent.store.redis;

import com.example.common.redis.RedisKeyspace;
import com.example.common.redis.RedisTtlPolicy;
import com.example.springai.service.agent.store.ConversationStore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 대화 히스토리를 Redis에 저장하는 ConversationStore 구현체.
 * Redis 장애 시 로컬 메모리 폴백을 사용한다.
 */
@Component
public class RedisConversationStore implements ConversationStore {

    private static final Logger logger = LoggerFactory.getLogger(RedisConversationStore.class);
    private static final String KEY_PREFIX = RedisKeyspace.AGENT_CONVERSATION_PREFIX;
    private static final java.time.Duration TTL = RedisTtlPolicy.STANDARD;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Map<String, List<String>> localFallback = new ConcurrentHashMap<>();

    public RedisConversationStore(ObjectProvider<StringRedisTemplate> redisTemplateProvider, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplateProvider.getIfAvailable();
        this.objectMapper = objectMapper;
    }

    /**
     * Redis에서 히스토리를 조회하고, 실패 시 로컬 폴백 값을 반환한다.
     */
    @Override
    public List<String> load(String sessionId) {
        if (redisTemplate == null) {
            return localCopy(sessionId);
        }
        return runOrDefault(() -> {
            String raw = redisTemplate.opsForValue().get(key(sessionId));
            if (raw == null || raw.isBlank()) {
                return localCopy(sessionId);
            }
            List<String> loaded = objectMapper.readValue(raw, new TypeReference<List<String>>() {});
            localFallback.put(sessionId, List.copyOf(loaded));
            return loaded;
        }, localCopy(sessionId), "load conversation", sessionId);
    }

    /**
     * Redis와 로컬 폴백에 히스토리를 함께 저장한다.
     */
    @Override
    public void save(String sessionId, List<String> messages) {
        List<String> safeMessages = messages == null ? Collections.emptyList() : messages;
        localFallback.put(sessionId, List.copyOf(safeMessages));
        if (redisTemplate == null) {
            return;
        }
        runSafely(() -> {
            String payload = objectMapper.writeValueAsString(safeMessages);
            redisTemplate.opsForValue().set(key(sessionId), payload, TTL);
        }, "save conversation", sessionId);
    }

    /**
     * Redis/로컬 폴백의 세션 히스토리를 함께 삭제한다.
     */
    @Override
    public void clear(String sessionId) {
        localFallback.remove(sessionId);
        if (redisTemplate == null) {
            return;
        }
        runSafely(() -> redisTemplate.delete(key(sessionId)), "clear conversation", sessionId);
    }

    private String key(String sessionId) {
        return KEY_PREFIX + sessionId;
    }

    private List<String> localCopy(String sessionId) {
        List<String> local = localFallback.get(sessionId);
        return local == null ? List.of() : List.copyOf(local);
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
