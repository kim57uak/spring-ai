package com.example.springai.service.chat;

import com.example.springai.config.LlmRateLimitProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 글로벌 LLM 호출 최소 간격 제한기
 */
@Component
public class LlmRequestRateLimiter {

    private final LlmRateLimitProperties properties;
    private long nextAllowedAtMs = 0L;

    public LlmRequestRateLimiter(LlmRateLimitProperties properties) {
        this.properties = properties;
    }

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
