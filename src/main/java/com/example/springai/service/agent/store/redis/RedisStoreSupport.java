package com.example.springai.service.agent.store.redis;

import org.slf4j.Logger;

/**
 * SpringAI Redis 저장소 공통 실행/예외 처리 지원 클래스.
 */
final class RedisStoreSupport {

    private final Logger logger;

    /**
     * @param logger Redis 작업 실패 로그를 남길 로거
     */
    RedisStoreSupport(Logger logger) {
        this.logger = logger;
    }

    /**
     * 예외 발생 시 fallback을 반환한다.
     *
     * @param action     실행할 동작
     * @param fallback   실패 시 반환값
     * @param actionName 로그 액션 이름
     * @param sessionId  세션 식별자
     * @return action 결과 또는 fallback
     */
    <T> T runOrDefault(ThrowingSupplier<T> action, T fallback, String actionName, String sessionId) {
        try {
            return action.get();
        } catch (Exception ex) {
            logger.warn("Failed to {} in Redis for session {}: {}", actionName, sessionId, ex.getMessage());
            return fallback;
        }
    }

    /**
     * 예외를 삼키고 warning 로그만 남긴다.
     *
     * @param action     실행할 동작
     * @param actionName 로그 액션 이름
     * @param sessionId  세션 식별자
     */
    void runSafely(ThrowingRunnable action, String actionName, String sessionId) {
        try {
            action.run();
        } catch (Exception ex) {
            logger.warn("Failed to {} in Redis for session {}: {}", actionName, sessionId, ex.getMessage());
        }
    }

    @FunctionalInterface
    interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    @FunctionalInterface
    interface ThrowingRunnable {
        void run() throws Exception;
    }
}
