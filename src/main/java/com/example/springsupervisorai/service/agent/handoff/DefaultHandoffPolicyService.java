package com.example.springsupervisorai.service.agent.handoff;

import com.example.springsupervisorai.config.A2aSupervisorRoutingProperties;
import com.example.springsupervisorai.model.DownstreamCallResult;
import com.example.springsupervisorai.model.HandoffPolicyContext;
import com.example.springsupervisorai.model.HandoffDirective;
import com.example.springsupervisorai.model.HandoffValidationResult;
import com.example.springsupervisorai.model.RoutingPlan;
import com.example.springsupervisorai.model.SupervisorA2aMethod;
import com.example.springsupervisorai.model.SupervisorPlanningContext;
import com.example.springsupervisorai.service.agent.invoke.DownstreamAgentCardCache;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * handoff 정책 검증 기본 구현체.
 * <p>
 * 본 구현은 실행 안전성을 우선한다.
 * 검증 실패 시 handoff를 차단하고 기존 planner 경로를 유지한다.
 */
@Component
public class DefaultHandoffPolicyService implements HandoffPolicyService {

    static final String REASON_FLAG_DISABLED = "FLAG_DISABLED";
    static final String REASON_EMPTY_TARGET = "EMPTY_TARGET";
    static final String REASON_UNKNOWN_AGENT = "UNKNOWN_AGENT";
    static final String REASON_METHOD_NOT_ALLOWED = "METHOD_NOT_ALLOWED";
    static final String REASON_STREAM_NOT_SUPPORTED = "STREAM_NOT_SUPPORTED";
    static final String REASON_HOP_LIMIT = "HOP_LIMIT_REACHED";
    static final String REASON_DUPLICATE_PATH = "DUPLICATE_PATH_BLOCKED";
    static final String REASON_RATE_LIMIT = "RATE_LIMIT_BLOCKED";

    private static final String FACT_HANDOFF_HOP_COUNT = "handoffHopCount";
    private static final String FACT_HANDOFF_PATH = "handoffPath";
    private static final String FACT_HANDOFF_WINDOW_START_EPOCH_MS = "handoffWindowStartEpochMs";
    private static final String FACT_HANDOFF_WINDOW_COUNT = "handoffWindowCount";

    private final A2aSupervisorRoutingProperties routingProperties;
    private final DownstreamAgentCardCache downstreamAgentCardCache;

    public DefaultHandoffPolicyService(
            A2aSupervisorRoutingProperties routingProperties,
            DownstreamAgentCardCache downstreamAgentCardCache
    ) {
        this.routingProperties = routingProperties;
        this.downstreamAgentCardCache = downstreamAgentCardCache;
    }

    /**
     * invoke 배치에서 보고된 handoff 지시를 정책 기준으로 일괄 평가한다.
     * <p>
     * 동작 규칙:
     * - handoff 지시가 없는 결과는 무시한다.
     * - 각 directive는 allowlist/method/hop/path/rate-limit 순서로 검증한다.
     * - 검증 실패는 rejected 결과로 반환하며 planner 경로 유지를 유도한다.
     *
     * @param context 현재 supervisor 실행 컨텍스트
     * @param batchResults 직전 invoke 배치 결과
     * @return directive별 검증 결과 목록(순서 보존)
     */
    @Override
    public List<HandoffValidationResult> evaluate(HandoffPolicyContext context) {
        SupervisorPlanningContext planningContext = context.planningContext();
        List<DownstreamCallResult> batchResults = context.batchResults();
        if (batchResults == null || batchResults.isEmpty()) {
            return List.of();
        }
        int hopCount = currentHopCount(planningContext.getSwarmSharedFacts());
        List<String> handoffPath = handoffPath(planningContext.getSwarmSharedFacts());
        List<HandoffValidationResult> results = new ArrayList<>();
        for (DownstreamCallResult callResult : batchResults) {
            HandoffDirective directive = toDirective(callResult);
            if (directive == null) {
                continue;
            }
            results.add(validateDirective(planningContext, directive, hopCount, handoffPath));
        }
        return results;
    }

    /**
     * 단일 handoff directive를 정책 제약으로 검증해 허용/거부 결과를 생성한다.
     *
     * @param context 실행 컨텍스트
     * @param directive 검증 대상 handoff 지시
     * @param hopCount 현재까지 누적 hop 수
     * @param handoffPath 최근 handoff 경로
     * @return 정책 검증 결과(accepted 또는 rejected)
     */
    private HandoffValidationResult validateDirective(
            SupervisorPlanningContext context,
            HandoffDirective directive,
            int hopCount,
            List<String> handoffPath
    ) {
        A2aSupervisorRoutingProperties.Handoff handoff = routingProperties.getHandoff();
        if (handoff == null || !handoff.isEnabled()) {
            return HandoffValidationResult.rejected(REASON_FLAG_DISABLED, directive, hopCount);
        }

        String nextAgent = safe(directive.nextAgentKey());
        if (nextAgent.isBlank()) {
            return HandoffValidationResult.rejected(REASON_EMPTY_TARGET, directive, hopCount);
        }
        if (!routingProperties.getRouting().containsKey(nextAgent)) {
            return HandoffValidationResult.rejected(REASON_UNKNOWN_AGENT, directive, hopCount);
        }

        String method = safe(directive.method());
        if (method.isBlank()) {
            method = SupervisorA2aMethod.preferredSendMethod();
        }
        SupervisorA2aMethod parsedMethod = SupervisorA2aMethod.from(method).orElse(null);
        if (parsedMethod == null) {
            return HandoffValidationResult.rejected(REASON_METHOD_NOT_ALLOWED, directive, hopCount);
        }
        Set<String> allowedMethods = effectiveAllowedMethods(handoff);
        if (!allowedMethods.contains(method)) {
            return HandoffValidationResult.rejected(REASON_METHOD_NOT_ALLOWED, directive, hopCount);
        }
        if (parsedMethod.isStream()
                && !downstreamAgentCardCache.supportsStreaming(nextAgent)) {
            return HandoffValidationResult.rejected(REASON_STREAM_NOT_SUPPORTED, directive, hopCount);
        }

        int nextHop = hopCount + 1;
        if (nextHop > Math.max(1, handoff.getMaxHops())) {
            return HandoffValidationResult.rejected(REASON_HOP_LIMIT, directive, hopCount);
        }
        if (isBlockedByRecentPath(nextAgent, handoffPath, Math.max(0, handoff.getBlockSameAgentWithinSteps()))) {
            return HandoffValidationResult.rejected(REASON_DUPLICATE_PATH, directive, hopCount);
        }
        if (isRateLimited(context.getSwarmSharedFacts(), handoff)) {
            return HandoffValidationResult.rejected(REASON_RATE_LIMIT, directive, hopCount);
        }

        RoutingPlan plan = new RoutingPlan(
                nextAgent,
                method,
                safe(directive.reason()).isBlank() ? "handoff requested by " + safe(directive.fromAgentKey()) : directive.reason(),
                1,
                directive.arguments() == null ? Map.of() : directive.arguments(),
                "HANDOFF",
                nextHop,
                safe(directive.fromAgentKey())
        );
        return HandoffValidationResult.accepted(
                new HandoffDirective(
                        safe(directive.fromAgentKey()),
                        nextAgent,
                        method,
                        safe(directive.reason()),
                        directive.arguments() == null ? Map.of() : Map.copyOf(directive.arguments())
                ),
                plan,
                nextHop
        );
    }

    /**
     * downstream 결과에서 handoff 지시를 정규화해 값 객체로 변환한다.
     *
     * @param result downstream 호출 결과
     * @return handoff 지시가 없으면 null, 있으면 정규화된 directive
     */
    private HandoffDirective toDirective(DownstreamCallResult result) {
        if (result == null || !result.handoffRequested()) {
            return null;
        }
        Map<String, Object> arguments = result.handoffArguments() == null ? Map.of() : Map.copyOf(result.handoffArguments());
        return new HandoffDirective(
                safe(result.agentKey()),
                safe(result.nextAgentKey()),
                safe(result.handoffMethod()),
                safe(result.handoffReason()),
                arguments
        );
    }

    /**
     * 최근 handoff 경로 윈도우에서 동일 agent 재방문 차단 여부를 확인한다.
     *
     * @param nextAgent 다음 호출 후보 agent key
     * @param handoffPath 누적 handoff 경로
     * @param recentWindow 검사할 최근 경로 크기
     * @return recentWindow 구간에 동일 agent가 이미 있으면 true
     */
    private boolean isBlockedByRecentPath(String nextAgent, List<String> handoffPath, int recentWindow) {
        if (handoffPath == null || handoffPath.isEmpty() || recentWindow <= 0) {
            return false;
        }
        int start = Math.max(0, handoffPath.size() - recentWindow);
        for (int i = start; i < handoffPath.size(); i++) {
            if (safe(handoffPath.get(i)).equalsIgnoreCase(nextAgent)) {
                return true;
            }
        }
        return false;
    }

    /**
     * swarm facts에서 현재 handoff hop 수를 읽는다.
     *
     * @param facts swarm 공유 facts
     * @return 음수 보정이 적용된 hop 수
     */
    private int currentHopCount(Map<String, Object> facts) {
        Object raw = facts == null ? null : facts.get(FACT_HANDOFF_HOP_COUNT);
        if (raw instanceof Number number) {
            return Math.max(0, number.intValue());
        }
        try {
            return Math.max(0, Integer.parseInt(String.valueOf(raw)));
        } catch (Exception ignored) {
            return 0;
        }
    }

    /**
     * 분당 handoff 허용 횟수 초과 여부를 계산한다.
     *
     * @param facts swarm 공유 facts
     * @param handoff handoff 정책 설정
     * @return 현재 1분 윈도우에서 한도를 초과하면 true
     */
    private boolean isRateLimited(Map<String, Object> facts, A2aSupervisorRoutingProperties.Handoff handoff) {
        int maxPerMinute = Math.max(1, handoff.getMaxPerMinute());
        int windowCount = intFact(facts, FACT_HANDOFF_WINDOW_COUNT);
        long windowStartEpochMs = longFact(facts, FACT_HANDOFF_WINDOW_START_EPOCH_MS);
        long now = System.currentTimeMillis();
        if (windowStartEpochMs <= 0 || now - windowStartEpochMs >= 60_000L) {
            return false;
        }
        return windowCount >= maxPerMinute;
    }

    /**
     * handoff 전용 허용 메서드가 지정되었는지 확인해 최종 allowlist를 계산한다.
     *
     * @param handoff handoff 정책 설정
     * @return 검증에 사용할 method allowlist
     */
    private Set<String> effectiveAllowedMethods(A2aSupervisorRoutingProperties.Handoff handoff) {
        if (handoff != null && handoff.getAllowMethods() != null && !handoff.getAllowMethods().isEmpty()) {
            return Set.copyOf(handoff.getAllowMethods());
        }
        return Set.copyOf(routingProperties.getAllowedMethods());
    }

    private int intFact(Map<String, Object> facts, String key) {
        Object raw = facts == null ? null : facts.get(key);
        if (raw instanceof Number number) {
            return Math.max(0, number.intValue());
        }
        try {
            return Math.max(0, Integer.parseInt(String.valueOf(raw)));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private long longFact(Map<String, Object> facts, String key) {
        Object raw = facts == null ? null : facts.get(key);
        if (raw instanceof Number number) {
            return Math.max(0L, number.longValue());
        }
        try {
            return Math.max(0L, Long.parseLong(String.valueOf(raw)));
        } catch (Exception ignored) {
            return 0L;
        }
    }

    /**
     * swarm facts의 handoffPath 값을 안전하게 문자열 리스트로 정규화한다.
     *
     * @param facts swarm 공유 facts
     * @return 소문자 정규화가 적용된 handoff 경로
     */
    @SuppressWarnings("unchecked")
    private List<String> handoffPath(Map<String, Object> facts) {
        Object raw = facts == null ? null : facts.get(FACT_HANDOFF_PATH);
        if (!(raw instanceof List<?> source) || source.isEmpty()) {
            return List.of();
        }
        ArrayList<String> converted = new ArrayList<>();
        for (Object value : source) {
            String normalized = safe(value == null ? "" : String.valueOf(value)).toLowerCase(Locale.ROOT);
            if (!normalized.isBlank()) {
                converted.add(normalized);
            }
        }
        return List.copyOf(converted);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
