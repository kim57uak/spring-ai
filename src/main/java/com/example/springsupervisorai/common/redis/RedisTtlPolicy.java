package com.example.springsupervisorai.common.redis;

import java.time.Duration;

/**
 * Supervisor AI 애플리케이션 전용 Redis TTL 정책 상수.
 */
public final class RedisTtlPolicy {

    private RedisTtlPolicy() {
    }

    public static final Duration STANDARD = Duration.ofMinutes(30);
    public static final Duration SWARM_STATE = Duration.ofHours(1);
}
