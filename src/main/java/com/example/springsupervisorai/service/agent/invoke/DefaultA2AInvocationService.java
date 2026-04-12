package com.example.springsupervisorai.service.agent.invoke;

import com.example.springsupervisorai.a2a.A2AJsonRpcClient;
import com.example.springsupervisorai.a2a.A2ARequestMapper;
import com.example.springsupervisorai.a2a.dto.JsonRpcRequest;
import com.example.springsupervisorai.config.A2aSupervisorRoutingProperties;
import com.example.springsupervisorai.model.DownstreamCallResult;
import com.example.springsupervisorai.model.RoutingPlan;
import com.example.springsupervisorai.model.SupervisorErrorCode;
import com.example.springsupervisorai.model.SupervisorInvocationStatus;
import com.example.springsupervisorai.model.SupervisorA2aMethod;
import com.example.springsupervisorai.model.SupervisorPlanningContext;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * supervisor downstream 호출을 수행하는 기본 invocation 어댑터.
 * <p>
 * 처리 책임:
 * - 라우트 타겟 해석
 * - JSON-RPC 요청 매핑
 * - retry/backoff 정책 적용
 * - downstream 응답을 표준 DownstreamCallResult로 정규화
 * - per-agent circuit breaker 적용(연속 장애 시 일시 차단)
 */
@Component
public class DefaultA2AInvocationService implements A2AInvocationService {

    private static final Logger logger = LoggerFactory.getLogger(DefaultA2AInvocationService.class);
    private final A2AClientRegistry clientRegistry;
    private final A2ARequestMapper requestMapper;
    private final A2AJsonRpcClient jsonRpcClient;
    private final ConcurrentMap<String, CircuitState> circuitStateByAgent = new ConcurrentHashMap<>();

    /**
     * invocation 의존성을 생성자 주입으로 초기화한다.
     *
     * @param clientRegistry 라우팅 정책 레지스트리
     * @param requestMapper 도메인 -> JSON-RPC 요청 매퍼
     * @param jsonRpcClient HTTP JSON-RPC 클라이언트
     */
    public DefaultA2AInvocationService(
            A2AClientRegistry clientRegistry,
            A2ARequestMapper requestMapper,
            A2AJsonRpcClient jsonRpcClient
    ) {
        this.clientRegistry = clientRegistry;
        this.requestMapper = requestMapper;
        this.jsonRpcClient = jsonRpcClient;
    }

    /**
     * 단일 routing plan에 대해 downstream A2A 호출을 수행한다.
     * <p>
     * 호출 순서:
     * - circuit open 여부를 먼저 확인한다(open이면 즉시 실패 반환).
     * - open이 아니면 retry 정책 범위 내에서 downstream 호출을 시도한다.
     * - 재시도 포함 최종 실패 시 circuit failure 카운트를 누적한다.
     * - 최종 성공 시 해당 agent의 circuit 상태를 초기화한다.
     *
     * @param plan 실행할 라우팅 계획
     * @param context planning 컨텍스트
     * @return 표준화된 downstream 호출 결과
     */
    @Override
    public DownstreamCallResult invoke(RoutingPlan plan, SupervisorPlanningContext context) {
        A2aSupervisorRoutingProperties.CircuitBreaker circuitBreaker = clientRegistry.circuitBreakerPolicy();
        if (isCircuitOpen(plan.agentKey(), circuitBreaker)) {
            logger.warn("Supervisor circuit open sessionId={}, agentKey={}", context.getSessionId(), plan.agentKey());
            return new DownstreamCallResult(
                    plan.agentKey(),
                    "",
                    SupervisorInvocationStatus.FAILED.value(),
                    "",
                    SupervisorErrorCode.CIRCUIT_OPEN.value(),
                    "Circuit breaker is open for downstream agent: " + plan.agentKey()
            );
        }

        A2AClientRegistry.A2ARouteTarget target = clientRegistry.resolve(plan.agentKey(), plan.method());
        A2aSupervisorRoutingProperties.Retry retry = clientRegistry.retryPolicy();
        int maxRetries = Math.max(0, retry.getMaxRetries());
        logger.info("Supervisor invoke start sessionId={}, agentKey={}, method={}, endpoint={}, maxRetries={}",
                context.getSessionId(), plan.agentKey(), target.method(), target.endpoint(), maxRetries);

        RuntimeException lastError = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            java.util.List<String> methodCandidates = methodCandidates(target.method());
            for (int index = 0; index < methodCandidates.size(); index++) {
                String candidateMethod = methodCandidates.get(index);
                A2AClientRegistry.A2ARouteTarget candidateTarget =
                        new A2AClientRegistry.A2ARouteTarget(target.agentKey(), target.endpoint(), candidateMethod, target.timeout());
                JsonRpcRequest candidateRequest = requestMapper.toJsonRpcRequest(plan, context, candidateMethod);
                try {
                    if (SupervisorA2aMethod.from(candidateMethod).map(SupervisorA2aMethod::isStream).orElse(false)) {
                        String streamPayload = jsonRpcClient.callStream(candidateTarget, candidateRequest, context.getSessionId());
                        markSuccess(plan.agentKey());
                        logger.info("Supervisor invoke success sessionId={}, agentKey={}, method={}",
                                context.getSessionId(), plan.agentKey(), candidateMethod);
                        return new DownstreamCallResult(
                                plan.agentKey(),
                                "",
                                SupervisorInvocationStatus.COMPLETED.value(),
                                streamPayload,
                                "",
                                ""
                        );
                    }
                    JsonNode response = jsonRpcClient.call(candidateTarget, candidateRequest, context.getSessionId());
                    DownstreamCallResult result = normalize(plan.agentKey(), response);
                    if (isMethodNotFound(result) && index < methodCandidates.size() - 1) {
                        logger.warn("Supervisor invoke method fallback sessionId={}, agentKey={}, fromMethod={}, toMethod={}",
                                context.getSessionId(), plan.agentKey(), candidateMethod, methodCandidates.get(index + 1));
                        continue;
                    }
                    markSuccess(plan.agentKey());
                    logger.info("Supervisor invoke success sessionId={}, agentKey={}, method={}",
                            context.getSessionId(), plan.agentKey(), candidateMethod);
                    return result;
                } catch (RuntimeException ex) {
                    lastError = ex;
                    if (isTimeout(ex)) {
                        // 타임아웃 발생 시 동일 세션 컨텍스트를 가진 downstream 상태를 정리해 후속 요청에 누수가 없게 한다.
                        jsonRpcClient.clearSession(candidateTarget, context.getSessionId());
                    }
                    logger.warn("Supervisor invoke attempt failed sessionId={}, agentKey={}, method={}, attempt={}, error={}",
                            context.getSessionId(), plan.agentKey(), candidateMethod, attempt + 1, ex.getMessage());
                }
            }
            if (attempt == maxRetries) {
                break;
            }
            backoff(attempt, retry);
        }
        markFailure(plan.agentKey(), circuitBreaker);
        logger.error("Supervisor invoke failed sessionId={}, agentKey={}, error={}",
                context.getSessionId(), plan.agentKey(), lastError == null ? "Downstream call failed" : lastError.getMessage());
        return new DownstreamCallResult(
                plan.agentKey(),
                "",
                SupervisorInvocationStatus.FAILED.value(),
                "",
                SupervisorErrorCode.DOWNSTREAM_UNAVAILABLE.value(),
                lastError == null ? "Downstream call failed" : lastError.getMessage()
        );
    }

    /**
     * retry 시도 번호 기반 백오프 지연을 적용한다.
     *
     * @param attempt 현재 재시도 횟수(0-based)
     * @param retry retry 정책
     */
    private void backoff(int attempt, A2aSupervisorRoutingProperties.Retry retry) {
        long raw = retry.getInitialBackoffMs() * (1L << Math.min(attempt, 10));
        long delay = Math.min(raw, retry.getMaxBackoffMs());
        try {
            Thread.sleep(Math.max(50L, delay));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean isTimeout(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof TimeoutException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && (message.contains("timed out") || message.contains("Timeout on blocking read"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * 메서드 호환 후보 목록을 생성한다.
     * <p>
     * 기본 전략:
     * - configured method를 1순위로 시도
     * - method not found(-32601)일 때에만 구/신 메서드로 1회 폴백
     * 예: `message/send` -> `SendMessage`, `SendStreamingMessage` -> `message/stream`
     *
     * @param method 라우팅 설정/플랜에서 선택된 메서드
     * @return 시도 순서가 보존된 후보 목록
     */
    private java.util.List<String> methodCandidates(String method) {
        return SupervisorA2aMethod.from(method)
                .map(parsed -> {
                    if (parsed == SupervisorA2aMethod.SEND_MESSAGE) {
                        return java.util.List.of(SupervisorA2aMethod.SEND_MESSAGE.value(), SupervisorA2aMethod.MESSAGE_SEND.value());
                    }
                    if (parsed == SupervisorA2aMethod.MESSAGE_SEND) {
                        return java.util.List.of(SupervisorA2aMethod.MESSAGE_SEND.value(), SupervisorA2aMethod.SEND_MESSAGE.value());
                    }
                    if (parsed == SupervisorA2aMethod.SEND_STREAMING_MESSAGE) {
                        return java.util.List.of(SupervisorA2aMethod.SEND_STREAMING_MESSAGE.value(), SupervisorA2aMethod.MESSAGE_STREAM.value());
                    }
                    if (parsed == SupervisorA2aMethod.MESSAGE_STREAM) {
                        return java.util.List.of(SupervisorA2aMethod.MESSAGE_STREAM.value(), SupervisorA2aMethod.SEND_STREAMING_MESSAGE.value());
                    }
                    if (parsed == SupervisorA2aMethod.GET_TASK) {
                        return java.util.List.of(SupervisorA2aMethod.GET_TASK.value(), SupervisorA2aMethod.TASKS_GET.value());
                    }
                    if (parsed == SupervisorA2aMethod.TASKS_GET) {
                        return java.util.List.of(SupervisorA2aMethod.TASKS_GET.value(), SupervisorA2aMethod.GET_TASK.value());
                    }
                    if (parsed == SupervisorA2aMethod.LIST_TASKS) {
                        return java.util.List.of(SupervisorA2aMethod.LIST_TASKS.value(), SupervisorA2aMethod.TASKS_LIST.value());
                    }
                    if (parsed == SupervisorA2aMethod.TASKS_LIST) {
                        return java.util.List.of(SupervisorA2aMethod.TASKS_LIST.value(), SupervisorA2aMethod.LIST_TASKS.value());
                    }
                    if (parsed == SupervisorA2aMethod.CANCEL_TASK) {
                        return java.util.List.of(SupervisorA2aMethod.CANCEL_TASK.value(), SupervisorA2aMethod.TASKS_CANCEL.value());
                    }
                    if (parsed == SupervisorA2aMethod.TASKS_CANCEL) {
                        return java.util.List.of(SupervisorA2aMethod.TASKS_CANCEL.value(), SupervisorA2aMethod.CANCEL_TASK.value());
                    }
                    return java.util.List.of(method);
                })
                .orElseGet(() -> java.util.List.of(method));
    }

    /**
     * downstream 에러가 JSON-RPC method not found인지 판별한다.
     *
     * @param result 정규화된 호출 결과
     * @return -32601이면 true
     */
    private boolean isMethodNotFound(DownstreamCallResult result) {
        return result != null && "-32601".equals(result.errorCode());
    }

    /**
     * downstream JSON-RPC 응답을 표준 결과 객체로 정규화한다.
     *
     * @param agentKey 호출 대상 agent key
     * @param response downstream 원시 응답
     * @return 표준 DownstreamCallResult
     */
    private DownstreamCallResult normalize(String agentKey, JsonNode response) {
        if (response == null || response.isNull()) {
            return new DownstreamCallResult(
                    agentKey,
                    "",
                    SupervisorInvocationStatus.FAILED.value(),
                    "",
                    SupervisorErrorCode.EMPTY_RESPONSE.value(),
                    "Downstream returned empty response"
            );
        }
        JsonNode error = response.path("error");
        if (!error.isMissingNode() && !error.isNull()) {
            String code = error.path("code").asText(SupervisorErrorCode.DOWNSTREAM_ERROR.value());
            String message = error.path("message").asText("Downstream error");
            return new DownstreamCallResult(agentKey, "", SupervisorInvocationStatus.FAILED.value(), "", code, message);
        }

        JsonNode result = response.path("result");
        String taskId = readAny(result, "id", "");
        String status = readAny(result, "status", SupervisorInvocationStatus.COMPLETED.value());
        String payload = result.isMissingNode() ? response.toString() : result.toString();

        return new DownstreamCallResult(agentKey, taskId, status, payload, "", "");
    }

    /**
     * JSON node에서 지정 필드를 문자열로 안전하게 읽는다.
     *
     * @param node 조회 대상 노드
     * @param field 필드명
     * @param fallback 기본값
     * @return 추출 문자열 또는 fallback
     */
    private String readAny(JsonNode node, String field, String fallback) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return fallback;
        }
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return fallback;
        }
        return value.asText(fallback);
    }

    /**
     * circuit breaker가 열려 있는지 확인한다.
     * <p>
     * open 상태 만료 시에는 자동으로 half-open 성격(단일 재시도 허용)으로 전환한다.
     * 즉, 별도 관리자 작업 없이 openDuration 경과 후 호출이 다시 흐른다.
     *
     * @param agentKey downstream agent key
     * @param policy circuit breaker 정책
     * @return 회로가 열려 있어 즉시 차단해야 하면 true
     */
    private boolean isCircuitOpen(String agentKey, A2aSupervisorRoutingProperties.CircuitBreaker policy) {
        if (!policy.isEnabled()) {
            return false;
        }
        CircuitState state = circuitStateByAgent.computeIfAbsent(agentKey, key -> new CircuitState());
        long now = Instant.now().toEpochMilli();
        if (state.openUntilEpochMs <= 0) {
            return false;
        }
        if (state.openUntilEpochMs > now) {
            return true;
        }
        state.openUntilEpochMs = 0;
        state.consecutiveFailures = 0;
        return false;
    }

    /**
     * downstream 호출 성공 시 circuit breaker 실패 카운트를 초기화한다.
     *
     * @param agentKey downstream agent key
     */
    private void markSuccess(String agentKey) {
        circuitStateByAgent.computeIfAbsent(agentKey, key -> new CircuitState())
                .reset();
    }

    /**
     * downstream 호출 실패 시 circuit breaker 상태를 갱신한다.
     * <p>
     * 주의:
     * - 이 메서드는 "재시도 포함 최종 실패"일 때만 호출된다.
     * - 임계치 도달 시 openUntil을 현재 시각 + openDuration으로 설정하고 연속 실패 카운터를 리셋한다.
     *
     * @param agentKey downstream agent key
     * @param policy circuit breaker 정책
     */
    private void markFailure(String agentKey, A2aSupervisorRoutingProperties.CircuitBreaker policy) {
        if (!policy.isEnabled()) {
            return;
        }
        int threshold = Math.max(1, policy.getFailureThreshold());
        long openDurationMs = Math.max(1000L, policy.getOpenDurationMs());
        CircuitState state = circuitStateByAgent.computeIfAbsent(agentKey, key -> new CircuitState());
        state.consecutiveFailures++;
        if (state.consecutiveFailures >= threshold) {
            state.openUntilEpochMs = Instant.now().toEpochMilli() + openDurationMs;
            state.consecutiveFailures = 0;
        }
    }

    /**
     * per-agent circuit breaker 내부 상태 값 객체.
     */
    private static final class CircuitState {
        private int consecutiveFailures;
        private long openUntilEpochMs;

        private void reset() {
            consecutiveFailures = 0;
            openUntilEpochMs = 0;
        }
    }
}
