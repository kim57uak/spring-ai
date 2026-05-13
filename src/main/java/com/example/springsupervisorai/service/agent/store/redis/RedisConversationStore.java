package com.example.springsupervisorai.service.agent.store.redis;

import com.example.springsupervisorai.common.redis.RedisKeyspace;
import com.example.springsupervisorai.common.redis.RedisTtlPolicy;
import com.example.springsupervisorai.service.agent.store.ConversationStore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Supervisor 대화 히스토리를 Redis에 저장하는 구현체.
 * <p>
 * Redis 장애 또는 미구성 환경에서는 프로세스 메모리 폴백을 사용한다.
 */
@Component("supervisorRedisConversationStore")
public class RedisConversationStore implements ConversationStore {

    private static final Logger logger = LoggerFactory.getLogger(RedisConversationStore.class);
    private static final String KEY_PREFIX = RedisKeyspace.SUPERVISOR_CONVERSATION_PREFIX;
    private static final java.time.Duration TTL = RedisTtlPolicy.STANDARD;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final RedisStoreSupport storeSupport;
    private final Map<String, List<String>> localFallback = new ConcurrentHashMap<>();

    /**
     * @param redisTemplateProvider Redis 템플릿 제공자
     * @param objectMapper          JSON 직렬화/역직렬화 도구
     */
    public RedisConversationStore(ObjectProvider<StringRedisTemplate> redisTemplateProvider, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplateProvider.getIfAvailable();
        this.objectMapper = objectMapper;
        this.storeSupport = new RedisStoreSupport(logger);
    }

    /**
     * 세션 대화 히스토리를 조회한다.
     *
     * @param sessionId 세션 식별자
     * @return 대화 메시지 목록
     */
    @Override
    public List<String> load(String sessionId) {
        if (redisTemplate == null) {
            return localCopy(sessionId);
        }
        return storeSupport.runOrDefault(() -> {
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
     * 세션 대화 히스토리를 저장한다.
     * <p>
     * CAS (WATCH/MULTI/EXEC)를 통해 동시 저장 충돌을 감지한다.
     * 충돌 시 경고 로그 후 단순 SET으로 폴백한다.
     *
     * @param sessionId 세션 식별자
     * @param messages  저장할 메시지 목록
     */
    @Override
    public void save(String sessionId, List<String> messages) {
        List<String> safe = messages == null ? Collections.emptyList() : messages;
        if (redisTemplate != null) {
            storeSupport.runSafely(() -> {
                String redisKey = key(sessionId);
                String payload = objectMapper.writeValueAsString(safe);
                redisTemplate.execute(new SessionCallback<Void>() {
                    @Override
                    @SuppressWarnings("unchecked")
                    public Void execute(RedisOperations ops) throws DataAccessException {
                        ops.watch(redisKey);
                        ops.multi();
                        ops.opsForValue().set(redisKey, payload, TTL);
                        List<Object> exec = ops.exec();
                        if (exec == null) {
                            logger.warn("Concurrent save conflict for session {}; falling back to direct set", sessionId);
                            ops.opsForValue().set(redisKey, payload, TTL);
                        }
                        return null;
                    }
                });
            }, "save conversation", sessionId);
        }
        localFallback.put(sessionId, List.copyOf(safe));
    }

    /**
     * 세션 대화 히스토리를 삭제한다.
     *
     * @param sessionId 세션 식별자
     */
    @Override
    public void clear(String sessionId) {
        localFallback.remove(sessionId);
        if (redisTemplate == null) {
            return;
        }
        storeSupport.runSafely(() -> redisTemplate.delete(key(sessionId)), "clear conversation", sessionId);
    }

    private String key(String sessionId) {
        return KEY_PREFIX + sessionId;
    }

    private List<String> localCopy(String sessionId) {
        List<String> local = localFallback.get(sessionId);
        return local == null ? List.of() : List.copyOf(local);
    }
}
