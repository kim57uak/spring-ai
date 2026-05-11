package com.example.springsupervisorai.service.resilience;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * 간단한 서킷 브레이커 유틸리티 클래스
 */
public class CircuitBreakerUtils {

    // 서킷 브레이커 상태
    public enum CircuitState {
        CLOSED, OPEN, HALF_OPEN
    }

    // 서킷 브레이커 상태 관리 클래스
    public static class CircuitBreaker {
        private volatile CircuitState state = CircuitState.CLOSED;
        private final AtomicInteger failureCount = new AtomicInteger(0);
        private final AtomicInteger successCount = new AtomicInteger(0);
        private volatile long openUntil = 0;
        private final int failureThreshold;
        private final long resetTimeoutMillis;

        public CircuitBreaker(int failureThreshold, long resetTimeoutMillis) {
            this.failureThreshold = failureThreshold;
            this.resetTimeoutMillis = resetTimeoutMillis;
        }

        public <T> T execute(Supplier<T> supplier) {
            // 서킷이 열린 상태이고 타임아웃이 지나지 않았다면 실패 처리
            if (state == CircuitState.OPEN) {
                if (System.currentTimeMillis() < openUntil) {
                    throw new CircuitBreakerOpenException("Circuit breaker is open");
                } else {
                    // 타임아웃이 지났으면 반열린 상태로 전환
                    state = CircuitState.HALF_OPEN;
                }
            }

            try {
                T result = supplier.get();

                // 성공 시 카운트 증가
                if (state == CircuitState.HALF_OPEN) {
                    successCount.incrementAndGet();
                    // 반열린 상태에서 성공하면 닫힌 상태로 전환
                    if (successCount.get() >= 1) {
                        state = CircuitState.CLOSED;
                        failureCount.set(0);
                        successCount.set(0);
                    }
                } else {
                    // 실패 카운트 초기화
                    failureCount.set(0);
                }

                return result;
            } catch (Exception e) {
                // 실패 시 카운트 증가
                int failures = failureCount.incrementAndGet();

                // 실패 임계값을 초과하면 서킷 열기
                if (failures >= failureThreshold) {
                    state = CircuitState.OPEN;
                    openUntil = System.currentTimeMillis() + resetTimeoutMillis;
                    failureCount.set(0);
                    successCount.set(0);
                }

                throw e;
            }
        }

        public CircuitState getState() {
            return state;
        }
    }

    public static class CircuitBreakerOpenException extends RuntimeException {
        public CircuitBreakerOpenException(String message) {
            super(message);
        }
    }

    // A2A 서비스용 서킷 브레이커 인스턴스
    private static final CircuitBreaker A2A_CIRCUIT_BREAKER = new CircuitBreaker(3, 10000); // 3번 실패 시 10초 동안 열림

    // HITL 서비스용 서킷 브레이커 인스턴스
    private static final CircuitBreaker HITL_CIRCUIT_BREAKER = new CircuitBreaker(2, 15000); // 2번 실패 시 15초 동안 열림

    public static <T> T executeA2A(Supplier<T> supplier) {
        return A2A_CIRCUIT_BREAKER.execute(supplier);
    }

    public static <T> T executeHitl(Supplier<T> supplier) {
        return HITL_CIRCUIT_BREAKER.execute(supplier);
    }

    public static CircuitState getA2ACircuitState() {
        return A2A_CIRCUIT_BREAKER.getState();
    }

    public static CircuitState getHitlCircuitState() {
        return HITL_CIRCUIT_BREAKER.getState();
    }
}