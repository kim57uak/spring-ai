package com.example.springsupervisorai.a2a.idempotency;

import com.example.common.redis.RedisKeyspace;
import com.example.common.redis.RedisTtlPolicy;
import com.example.springsupervisorai.a2a.dto.JsonRpcResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * supervisor message/send 요청 중복 실행 방지 서비스.
 * <p>
 * - 동일 요청 키(method/requestId) 기준으로 in-flight 실행을 1회로 제한한다.
 * - 완료 응답은 짧은 TTL 동안 캐시해 재전송 요청에 재사용한다.
 */
@Component
public class SupervisorRequestIdempotencyService {

    private static final Logger logger = LoggerFactory.getLogger(SupervisorRequestIdempotencyService.class);
    // 요청하신 운영 기준: idempotency 응답/락 TTL 30분 통일
    private static final Duration COMPLETED_TTL = RedisTtlPolicy.STANDARD;
    private static final Duration LOCK_TTL = RedisTtlPolicy.STANDARD;
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(130);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(120);
    private static final String RESPONSE_PREFIX = RedisKeyspace.IDEMPOTENCY_SUPERVISOR_RESPONSE_PREFIX;
    private static final String LOCK_PREFIX = RedisKeyspace.IDEMPOTENCY_SUPERVISOR_LOCK_PREFIX;

    private final ConcurrentMap<String, CompletableFuture<JsonRpcResponse>> inFlight = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CachedResponse> localFallback = new ConcurrentHashMap<>();
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 테스트/로컬 호환용 생성자.
     */
    public SupervisorRequestIdempotencyService() {
        this(null, new ObjectMapper());
    }

    @Autowired
    public SupervisorRequestIdempotencyService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    public JsonRpcResponse executeOnce(
            String sessionId,
            String method,
            Object requestId,
            Supplier<JsonRpcResponse> action
    ) {
        String key = dedupeKey(sessionId, method, requestId);
        if (key.isBlank()) {
            return action.get();
        }

        evictExpired();
        JsonRpcResponse cached = readCached(key);
        if (cached != null) {
            logger.info("Supervisor idempotency cache hit key={}", key);
            return cached;
        }

        CompletableFuture<JsonRpcResponse> future = new CompletableFuture<>();
        CompletableFuture<JsonRpcResponse> existing = inFlight.putIfAbsent(key, future);
        if (existing == null) {
            try {
                logger.info("Supervisor idempotency owner key={}", key);
                JsonRpcResponse response = runAsOwner(key, action);
                future.complete(response);
                return response;
            } catch (RuntimeException ex) {
                future.completeExceptionally(ex);
                throw ex;
            } finally {
                inFlight.remove(key, future);
            }
        }

        try {
            logger.info("Supervisor idempotency join in-flight key={}", key);
            return existing.get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for duplicate supervisor request", ex);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("Failed while waiting for duplicate supervisor request", cause);
        }
    }

    private String dedupeKey(String sessionId, String method, Object requestId) {
        if (requestId == null || method == null || method.isBlank()) {
            return "";
        }
        String id = String.valueOf(requestId).trim();
        if (id.isBlank()) {
            return "";
        }
        String safeSessionId = sessionId == null ? "" : sessionId.trim();
        // 세션 간 중복응답 오염 방지를 위해 dedupe key에 sessionId를 포함한다.
        return "SUPERVISOR|" + safeSessionId + "|" + method + "|" + id;
    }

    private void evictExpired() {
        localFallback.entrySet().removeIf(entry -> entry.getValue().isExpired());
    }

    private JsonRpcResponse runAsOwner(String key, Supplier<JsonRpcResponse> action) {
        if (redisTemplate == null) {
            JsonRpcResponse response = action.get();
            localFallback.put(key, new CachedResponse(response, Instant.now().plus(COMPLETED_TTL)));
            return response;
        }

        String lockKey = lockKey(key);
        Boolean lockAcquired = redisTemplate.opsForValue().setIfAbsent(lockKey, "1", LOCK_TTL);
        if (Boolean.TRUE.equals(lockAcquired)) {
            try {
                JsonRpcResponse response = action.get();
                storeCached(key, response);
                return response;
            } finally {
                redisTemplate.delete(lockKey);
            }
        }
        return waitForOwnerResult(key, action);
    }

    private JsonRpcResponse waitForOwnerResult(String key, Supplier<JsonRpcResponse> action) {
        long deadlineNanos = System.nanoTime() + WAIT_TIMEOUT.toNanos();
        while (System.nanoTime() < deadlineNanos) {
            JsonRpcResponse cached = readCached(key);
            if (cached != null) {
                return cached;
            }
            if (redisTemplate == null || Boolean.FALSE.equals(redisTemplate.hasKey(lockKey(key)))) {
                return runAsOwner(key, action);
            }
            try {
                TimeUnit.MILLISECONDS.sleep(POLL_INTERVAL.toMillis());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for supervisor idempotency owner", ex);
            }
        }
        logger.warn("Supervisor idempotency wait timeout key={}. Executing action as fallback.", key);
        JsonRpcResponse response = action.get();
        storeCached(key, response);
        return response;
    }

    private JsonRpcResponse readCached(String key) {
        if (redisTemplate != null) {
            try {
                String raw = redisTemplate.opsForValue().get(responseKey(key));
                if (raw != null && !raw.isBlank()) {
                    return objectMapper.readValue(raw, JsonRpcResponse.class);
                }
            } catch (Exception ex) {
                logger.warn("Supervisor idempotency redis read failed key={}: {}", key, ex.getMessage());
            }
        }
        CachedResponse fallback = localFallback.get(key);
        return fallback == null || fallback.isExpired() ? null : fallback.response();
    }

    private void storeCached(String key, JsonRpcResponse response) {
        localFallback.put(key, new CachedResponse(response, Instant.now().plus(COMPLETED_TTL)));
        if (redisTemplate == null) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(responseKey(key), objectMapper.writeValueAsString(response), COMPLETED_TTL);
        } catch (Exception ex) {
            logger.warn("Supervisor idempotency redis write failed key={}: {}", key, ex.getMessage());
        }
    }

    private String responseKey(String key) {
        return RESPONSE_PREFIX + key;
    }

    private String lockKey(String key) {
        return LOCK_PREFIX + key;
    }

    private record CachedResponse(JsonRpcResponse response, Instant expiresAt) {
        private boolean isExpired() {
            return expiresAt == null || Instant.now().isAfter(expiresAt);
        }
    }
}
