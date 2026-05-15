package com.example.springai.a2a.idempotency;

import com.example.springai.common.redis.RedisKeyspace;
import com.example.springai.common.redis.RedisTtlPolicy;
import com.example.springai.a2a.dto.JsonRpcResponse;
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
 * 동일 JSON-RPC 요청 키에 대해 중복 실행을 방지하는 idempotency 서비스.
 * <p>
 * - in-flight 요청은 최초 1회만 실행한다.
 * - 완료 응답은 짧은 TTL 동안 캐시해 재시도 요청에 재사용한다.
 */
@Component
public class A2aRequestIdempotencyService {

    private static final Logger logger = LoggerFactory.getLogger(A2aRequestIdempotencyService.class);
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(130);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(120);
    private static final String RESPONSE_PREFIX = RedisKeyspace.IDEMPOTENCY_A2A_RESPONSE_PREFIX;
    private static final String LOCK_PREFIX = RedisKeyspace.IDEMPOTENCY_A2A_LOCK_PREFIX;

    private final ConcurrentMap<String, CompletableFuture<JsonRpcResponse>> inFlight = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CachedResponse> localFallback = new ConcurrentHashMap<>();
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Duration completedTtl;
    private final Duration lockTtl;

    /**
     * 테스트/로컬 호환용 생성자.
     */
    public A2aRequestIdempotencyService() {
        this((StringRedisTemplate) null, new ObjectMapper(), null);
    }

    @Autowired
    public A2aRequestIdempotencyService(ObjectProvider<StringRedisTemplate> redisTemplateProvider, ObjectMapper objectMapper, RedisTtlPolicy ttlPolicy) {
        this(redisTemplateProvider.getIfAvailable(), objectMapper, ttlPolicy);
    }

    private A2aRequestIdempotencyService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper, RedisTtlPolicy ttlPolicy) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        Duration ttl = ttlPolicy != null ? ttlPolicy.getStandard() : Duration.ofMinutes(30);
        this.completedTtl = ttl;
        this.lockTtl = ttl;
    }

    /**
     * 동일 요청 키(scope/session/method/requestId)에 대해 단 한 번만 작업을 실행한다.
     * <p>
     * 처리 순서:
     * - 완료 캐시에 동일 키 응답이 있으면 즉시 재사용한다.
     * - in-flight 맵에서 실행 주체(owner)를 선점한 요청만 실제 action을 실행한다.
     * - 중복 요청은 선행 요청의 완료 결과를 대기 후 동일 응답을 반환한다.
     *
     * @param scopeName 에이전트 스코프명(예: SEARCH/PRODUCT)
     * @param sessionId 사용자 세션 식별자(로그/호출 문맥용)
     * @param method JSON-RPC 메서드명
     * @param requestId JSON-RPC 요청 id
     * @param action 실제 비즈니스 실행 로직
     * @return 중복 제거가 적용된 최종 JSON-RPC 응답
     */
    public JsonRpcResponse executeOnce(
            String scopeName,
            String sessionId,
            String method,
            Object requestId,
            Supplier<JsonRpcResponse> action
    ) {
        String key = dedupeKey(scopeName, sessionId, method, requestId);
        if (key.isBlank()) {
            return action.get();
        }

        evictExpired();
        JsonRpcResponse cached = readCached(key);
        if (cached != null) {
            logger.info("A2A idempotency cache hit key={}", key);
            return cached;
        }

        CompletableFuture<JsonRpcResponse> future = new CompletableFuture<>();
        CompletableFuture<JsonRpcResponse> existing = inFlight.putIfAbsent(key, future);
        if (existing == null) {
            try {
                logger.info("A2A idempotency owner key={}", key);
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
     * 중복 판정을 위한 내부 키를 생성한다.
     * <p>
     * requestId 또는 method가 비어 있으면 중복 판정이 불가능하므로 빈 문자열을 반환한다.
     * 동시 사용자 환경에서 응답 혼선을 막기 위해 sessionId를 키 구성요소에 포함한다.
     *
     * @param scopeName 스코프명
     * @param sessionId 세션 id
     * @param method JSON-RPC 메서드명
     * @param requestId JSON-RPC 요청 id
     * @return dedupe 키 또는 빈 문자열
     */
    private String dedupeKey(String scopeName, String sessionId, String method, Object requestId) {
        if (requestId == null || method == null || method.isBlank()) {
            return "";
        }
        String id = String.valueOf(requestId).trim();
        if (id.isBlank()) {
            return "";
        }
        String safeSessionId = sessionId == null ? "" : sessionId.trim();
        // 세션 분리 보장을 위해 dedupe key에 sessionId를 포함한다.
        return (scopeName == null ? "" : scopeName) + "|" + safeSessionId + "|" + method + "|" + id;
    }

    private void evictExpired() {
        localFallback.entrySet().removeIf(entry -> entry.getValue().isExpired());
    }

    private JsonRpcResponse runAsOwner(String key, Supplier<JsonRpcResponse> action) {
        if (redisTemplate == null) {
            JsonRpcResponse response = action.get();
            localFallback.put(key, new CachedResponse(response, Instant.now().plus(completedTtl)));
            return response;
        }

        String lockKey = lockKey(key);
        Boolean lockAcquired = redisTemplate.opsForValue().setIfAbsent(lockKey, "1", lockTtl);
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
                throw new IllegalStateException("Interrupted while waiting for idempotency owner", ex);
            }
        }
        logger.warn("A2A idempotency wait timeout key={}. Executing action as fallback.", key);
        JsonRpcResponse response = action.get();
        storeCached(key, response);
        return response;
    }

    /**
     * 이미 선점된 in-flight 요청의 완료 결과를 기다린다.
     *
     * @param key 요청 dedupe 키
     * @param existing 선행 요청 future
     * @return 선행 요청 결과
     */
    private JsonRpcResponse awaitInFlightResult(String key, CompletableFuture<JsonRpcResponse> existing) {
        try {
            logger.info("A2A idempotency join in-flight key={}", key);
            return existing.get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for duplicate A2A request", ex);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("Failed while waiting for duplicate A2A request", cause);
        }
    }

    private JsonRpcResponse readCached(String key) {
        if (redisTemplate != null) {
            try {
                String raw = redisTemplate.opsForValue().get(responseKey(key));
                if (raw != null && !raw.isBlank()) {
                    return objectMapper.readValue(raw, JsonRpcResponse.class);
                }
            } catch (Exception ex) {
                logger.warn("A2A idempotency redis read failed key={}: {}", key, ex.getMessage());
            }
        }
        CachedResponse fallback = localFallback.get(key);
        return fallback == null || fallback.isExpired() ? null : fallback.response();
    }

    private void storeCached(String key, JsonRpcResponse response) {
        localFallback.put(key, new CachedResponse(response, Instant.now().plus(completedTtl)));
        if (redisTemplate == null) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(responseKey(key), objectMapper.writeValueAsString(response), completedTtl);
        } catch (Exception ex) {
            logger.warn("A2A idempotency redis write failed key={}: {}", key, ex.getMessage());
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
     * @param response 캐시할 JSON-RPC 응답
     * @param expiresAt 캐시 만료 시각
     */
    private record CachedResponse(JsonRpcResponse response, Instant expiresAt) {
        /**
         * 현재 시각 기준으로 캐시가 만료되었는지 여부를 반환한다.
         *
         * @return 만료되었으면 true
         */
        private boolean isExpired() {
            return expiresAt == null || Instant.now().isAfter(expiresAt);
        }
    }
}
