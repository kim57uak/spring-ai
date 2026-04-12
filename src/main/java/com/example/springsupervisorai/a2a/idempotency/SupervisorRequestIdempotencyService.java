package com.example.springsupervisorai.a2a.idempotency;

import com.example.springsupervisorai.a2a.dto.JsonRpcResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
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
    private static final Duration COMPLETED_TTL = Duration.ofMinutes(2);

    private final ConcurrentMap<String, CompletableFuture<JsonRpcResponse>> inFlight = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CachedResponse> completed = new ConcurrentHashMap<>();

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
        CachedResponse cached = completed.get(key);
        if (cached != null && !cached.isExpired()) {
            logger.info("Supervisor idempotency cache hit key={}", key);
            return cached.response();
        }

        CompletableFuture<JsonRpcResponse> future = new CompletableFuture<>();
        CompletableFuture<JsonRpcResponse> existing = inFlight.putIfAbsent(key, future);
        if (existing == null) {
            try {
                logger.info("Supervisor idempotency owner key={}", key);
                JsonRpcResponse response = action.get();
                completed.put(key, new CachedResponse(response, Instant.now().plus(COMPLETED_TTL)));
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
        return "SUPERVISOR|" + safeSessionId + "|" + method + "|" + id;
    }

    private void evictExpired() {
        Iterator<Map.Entry<String, CachedResponse>> iterator = completed.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, CachedResponse> entry = iterator.next();
            if (entry.getValue().isExpired()) {
                iterator.remove();
            }
        }
    }

    private record CachedResponse(JsonRpcResponse response, Instant expiresAt) {
        private boolean isExpired() {
            return expiresAt == null || Instant.now().isAfter(expiresAt);
        }
    }
}
