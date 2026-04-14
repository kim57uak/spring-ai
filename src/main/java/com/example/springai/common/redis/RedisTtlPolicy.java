package com.example.springai.common.redis;

import java.time.Duration;

/**
 * Spring AI 애플리케이션 전용 Redis TTL 정책 상수.
 */
public final class RedisTtlPolicy {

    private RedisTtlPolicy() {
    }

    public static final Duration STANDARD = Duration.ofMinutes(30);
}
