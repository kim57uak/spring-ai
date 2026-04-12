package com.example.springsupervisorai.config;

import com.example.springsupervisorai.model.SupervisorA2aMethod;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@ConfigurationProperties(prefix = "host.a2a")
public class A2aSupervisorRoutingProperties {

    private Map<String, Route> routing = new LinkedHashMap<>();
    private Retry retry = new Retry();
    private CircuitBreaker circuitBreaker = new CircuitBreaker();
    private Execution execution = new Execution();
    private Set<String> allowedMethods = SupervisorA2aMethod.valuesSet();

    public Map<String, Route> getRouting() {
        return routing;
    }

    public void setRouting(Map<String, Route> routing) {
        this.routing = routing == null ? new LinkedHashMap<>() : routing;
    }

    public Retry getRetry() {
        return retry;
    }

    public void setRetry(Retry retry) {
        this.retry = retry == null ? new Retry() : retry;
    }

    public Set<String> getAllowedMethods() {
        return allowedMethods;
    }

    public void setAllowedMethods(Set<String> allowedMethods) {
        this.allowedMethods = allowedMethods == null || allowedMethods.isEmpty() ? this.allowedMethods : allowedMethods;
    }

    public CircuitBreaker getCircuitBreaker() {
        return circuitBreaker;
    }

    public void setCircuitBreaker(CircuitBreaker circuitBreaker) {
        this.circuitBreaker = circuitBreaker == null ? new CircuitBreaker() : circuitBreaker;
    }

    public Execution getExecution() {
        return execution;
    }

    public void setExecution(Execution execution) {
        this.execution = execution == null ? new Execution() : execution;
    }

    public static class Route {
        private String endpoint;
        private String method = SupervisorA2aMethod.MESSAGE_SEND.value();
        private int timeoutMs = 10_000;

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public String getMethod() {
            return method;
        }

        public void setMethod(String method) {
            this.method = method;
        }

        public int getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(int timeoutMs) {
            this.timeoutMs = timeoutMs;
        }
    }

    public static class Retry {
        private int maxRetries = 1;
        private long initialBackoffMs = 500;
        private long maxBackoffMs = 3_000;

        public int getMaxRetries() {
            return maxRetries;
        }

        public void setMaxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
        }

        public long getInitialBackoffMs() {
            return initialBackoffMs;
        }

        public void setInitialBackoffMs(long initialBackoffMs) {
            this.initialBackoffMs = initialBackoffMs;
        }

        public long getMaxBackoffMs() {
            return maxBackoffMs;
        }

        public void setMaxBackoffMs(long maxBackoffMs) {
            this.maxBackoffMs = maxBackoffMs;
        }
    }

    public static class CircuitBreaker {
        /**
         * Circuit Breaker 정책 설정.
         * <p>
         * Supervisor는 agentKey 단위로 회로 상태를 관리한다.
         * 동작 요약:
         * - enabled=false: 항상 호출을 통과시킨다(차단 비활성).
         * - enabled=true: 연속 실패가 failureThreshold 이상이면 회로를 open 한다.
         * - open 상태에서는 openDurationMs 동안 해당 agent 호출을 즉시 실패 처리한다.
         * - openDurationMs 경과 후 첫 호출 시 자동으로 half-open 성격으로 재시도(다시 호출 허용)한다.
         */
        private boolean enabled = true;
        /**
         * 회로 open 전까지 허용할 연속 실패 횟수.
         */
        private int failureThreshold = 3;
        /**
         * 회로 open 유지 시간(ms).
         */
        private long openDurationMs = 30_000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getFailureThreshold() {
            return failureThreshold;
        }

        public void setFailureThreshold(int failureThreshold) {
            this.failureThreshold = failureThreshold;
        }

        public long getOpenDurationMs() {
            return openDurationMs;
        }

        public void setOpenDurationMs(long openDurationMs) {
            this.openDurationMs = openDurationMs;
        }
    }

    /**
     * 실행 정책 설정.
     * <p>
     * maxConcurrency:
     * - 1: 순차 실행
     * - 2 이상: 최대 동시 실행 개수 제한
     */
    public static class Execution {
        private int maxConcurrency = 1;

        public int getMaxConcurrency() {
            return maxConcurrency;
        }

        public void setMaxConcurrency(int maxConcurrency) {
            this.maxConcurrency = maxConcurrency;
        }
    }
}
