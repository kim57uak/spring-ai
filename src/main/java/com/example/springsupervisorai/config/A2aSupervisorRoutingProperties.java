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
    private History history = new History();
    private Hitl hitl = new Hitl();
    private A2ui a2ui = new A2ui();
    private Handoff handoff = new Handoff();
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

    public History getHistory() {
        return history;
    }

    public void setHistory(History history) {
        this.history = history == null ? new History() : history;
    }

    public Hitl getHitl() {
        return hitl;
    }

    public void setHitl(Hitl hitl) {
        this.hitl = hitl == null ? new Hitl() : hitl;
    }

    public A2ui getA2ui() {
        return a2ui;
    }

    public void setA2ui(A2ui a2ui) {
        this.a2ui = a2ui == null ? new A2ui() : a2ui;
    }

    public Handoff getHandoff() {
        return handoff;
    }

    public void setHandoff(Handoff handoff) {
        this.handoff = handoff == null ? new Handoff() : handoff;
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

    /**
     * 대화 히스토리 프롬프트 주입 정책 설정.
     * <p>
     * maxTurns:
     * - LLM 프롬프트에 포함할 최근 대화 턴 수(user+assistant)를 제한한다.
     * - 메시지 저장 구조가 user/assistant 단위이므로 내부적으로 2배 메시지로 환산해 사용한다.
     */
    public static class History {
        private int maxTurns = 5;

        public int getMaxTurns() {
            return maxTurns;
        }

        public void setMaxTurns(int maxTurns) {
            this.maxTurns = maxTurns;
        }
    }

    /**
     * HITL 정책 메시지 표시 설정.
     * <p>
     * reasonMessages:
     * - HITL 정책 reason code를 사용자 노출용 메시지로 변환할 때 사용하는 맵.
     * - key는 code(lowercase), value는 UI에 보여줄 한글 문장.
     */
    public static class Hitl {
        private Map<String, String> reasonMessages = defaultReasonMessages();

        public Map<String, String> getReasonMessages() {
            return reasonMessages;
        }

        public void setReasonMessages(Map<String, String> reasonMessages) {
            this.reasonMessages = reasonMessages == null || reasonMessages.isEmpty()
                    ? defaultReasonMessages()
                    : new LinkedHashMap<>(reasonMessages);
        }

        private static Map<String, String> defaultReasonMessages() {
            LinkedHashMap<String, String> defaults = new LinkedHashMap<>();
            defaults.put("reservation_creation_request", "예약 생성 요청으로 판단되어 사용자 승인이 필요합니다.");
            defaults.put("data_mutation_detected", "데이터 변경 요청으로 판단되어 사용자 승인이 필요합니다.");
            defaults.put("high_risk_unknown_intent", "요청 의도가 불명확하고 위험도가 높아 사용자 승인이 필요합니다.");
            defaults.put("llm_review_required", "요청 위험도를 고려해 사용자 승인이 필요하다고 판단했습니다.");
            defaults.put("not_required_read_only", "조회성 요청으로 판단되어 승인이 필요하지 않습니다.");
            defaults.put("default", "요청 처리 전 사용자가 검토해야 하는 상황으로 판단되었습니다.");
            return defaults;
        }
    }

    public static class A2ui {
        private boolean enabled = false;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    /**
     * handoff 적용 정책 설정.
     * <p>
     * 운영 제어 목적:
     * - enabled: handoff 기능 토글
     * - maxHops: 세션 당 최대 handoff 체인 깊이 제한
     * - blockSameAgentWithinSteps: 최근 경로에서 동일 에이전트 재방문 제한
     * - maxPerMinute: 세션 당 분당 handoff 최대 허용 횟수
     * - allowMethods: handoff에서 허용할 메서드 allow-list
     */
    public static class Handoff {
        private boolean enabled = false;
        private int maxHops = 3;
        private int blockSameAgentWithinSteps = 2;
        private int maxPerMinute = 10;
        private Set<String> allowMethods = SupervisorA2aMethod.valuesSet();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMaxHops() {
            return maxHops;
        }

        public void setMaxHops(int maxHops) {
            this.maxHops = maxHops;
        }

        public int getBlockSameAgentWithinSteps() {
            return blockSameAgentWithinSteps;
        }

        public void setBlockSameAgentWithinSteps(int blockSameAgentWithinSteps) {
            this.blockSameAgentWithinSteps = blockSameAgentWithinSteps;
        }

        public int getMaxPerMinute() {
            return maxPerMinute;
        }

        public void setMaxPerMinute(int maxPerMinute) {
            this.maxPerMinute = maxPerMinute;
        }

        public Set<String> getAllowMethods() {
            return allowMethods;
        }

        public void setAllowMethods(Set<String> allowMethods) {
            this.allowMethods = allowMethods == null || allowMethods.isEmpty() ? this.allowMethods : allowMethods;
        }
    }
}
