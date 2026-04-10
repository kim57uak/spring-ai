package com.example.springai.service.chat;

import com.example.springai.config.LlmRateLimitProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 글로벌 LLM 호출 최소 간격 제한기
 * <p>
 * 스레드 안전한 방식으로 다음 허용 시각을 관리한다.
 */
@Component
public class LlmRequestRateLimiter {

    private final LlmRateLimitProperties properties;
    private long nextAllowedAtMs = 0L;

    public LlmRequestRateLimiter(LlmRateLimitProperties properties) {
        this.properties = properties;
    }

    /**
     * 다음 호출 가능 시각까지 대기해 최소 호출 간격을 보장한다.
     * <p>
     * rate-limit 비활성화 또는 간격 0이면 즉시 반환한다.
     */
    public synchronized void acquire() {
        if (!properties.isEnabled()) {
            return;
        }
        long minIntervalMs = Math.max(0L, properties.getMinIntervalMs());
        if (minIntervalMs == 0L) {
            return;
        }

        long now = System.currentTimeMillis();
        long waitMs = nextAllowedAtMs - now;
        if (waitMs > 0) {
            try {
                Thread.sleep(waitMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for LLM rate limit", e);
            }
            now = System.currentTimeMillis();
        }
        nextAllowedAtMs = now + minIntervalMs;
    }

    /**
     * 재시도 지연시간을 전역 허용 시각에 반영한다.
     * <p>
     * 기존 허용 시각보다 미래인 경우에만 확장한다.
     */
    public synchronized void applyBackoff(Duration delay) {
        long delayMs = Math.max(0L, delay.toMillis());
        if (delayMs == 0L) {
            return;
        }
        long target = System.currentTimeMillis() + delayMs;
        nextAllowedAtMs = Math.max(nextAllowedAtMs, target);
    }

    public int maxRetries() {
        return Math.max(0, properties.getMaxRetries());
    }

    public long initialBackoffMs() {
        return Math.max(1L, properties.getInitialBackoffMs());
    }

    public long maxBackoffMs() {
        return Math.max(initialBackoffMs(), properties.getMaxBackoffMs());
    }

    public long minIntervalMs() {
        return Math.max(0L, properties.getMinIntervalMs());
    }
}
