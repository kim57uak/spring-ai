package com.example.common.redis;

import java.time.Duration;

/**
 * Redis TTL 정책 상수.
 * <p>
 * 유지보수 편의를 위해 Redis 관련 만료 시간은 본 클래스에서 중앙 관리한다.
 */
public final class RedisTtlPolicy {

    private RedisTtlPolicy() {
    }

    /**
     * 기본 TTL(요청 기준: 30분 통일).
     */
    public static final Duration STANDARD = Duration.ofMinutes(30);

    /**
     * Swarm 상태 TTL.
     * 기존 운영 정책(1시간)을 유지한다.
     */
    public static final Duration SWARM_STATE = Duration.ofHours(1);
}
