package com.example.springsupervisorai.a2a.idempotency;

import com.example.springsupervisorai.common.redis.RedisKeyspace;
import com.example.springsupervisorai.common.redis.RedisTtlPolicy;
import com.example.springsupervisorai.a2a.dto.JsonRpcResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
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
 * Supervisor JSON-RPC 요청 중복 실행 방지 서비스.
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
        this((StringRedisTemplate) null, new ObjectMapper());
    }

    @Autowired
    public SupervisorRequestIdempotencyService(ObjectProvider<StringRedisTemplate> redisTemplateProvider, ObjectMapper objectMapper) {
        this(redisTemplateProvider.getIfAvailable(), objectMapper);
    }

    private SupervisorRequestIdempotencyService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    /**
     * 동일 요청 키(session/method/requestId)에 대해 단 한 번만 작업을 실행한다.
     *
     * @param sessionId 사용자 세션 식별자
     * @param method JSON-RPC 메서드명
     * @param requestId JSON-RPC 요청 id
     * @param action 실제 비즈니스 실행 로직
     * @return 중복 제거가 적용된 JSON-RPC 응답
     */
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

        return awaitInFlightResult(key, existing);
    }

    /**
     * 중복 판정용 내부 키를 생성한다.
     *
     * @param sessionId 사용자 세션 식별자
     * @param method JSON-RPC 메서드명
     * @param requestId JSON-RPC 요청 id
     * @return dedupe 키 또는 빈 문자열
     */
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

    /**
     * owner 락 획득 시 action을 실행하고, 락 미획득 시 owner 결과를 기다린다.
     */
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

    /**
     * 이미 선점된 in-flight 요청의 완료 결과를 기다린다.
     */
    private JsonRpcResponse awaitInFlightResult(String key, CompletableFuture<JsonRpcResponse> existing) {
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

    /**
     * 완료 캐시에서 응답을 조회한다.
     */
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

    /**
     * 완료 응답을 로컬/Redis 캐시에 저장한다.
     */
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

    /**
     * 완료 응답 캐시 값 객체.
     *
     * @param response 캐시 응답
     * @param expiresAt 만료 시각
     */
    private record CachedResponse(JsonRpcResponse response, Instant expiresAt) {
        /**
         * 현재 시각 기준 만료 여부를 반환한다.
         *
         * @return 만료되었으면 true
         */
        private boolean isExpired() {
            return expiresAt == null || Instant.now().isAfter(expiresAt);
        }
    }
}
