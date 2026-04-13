package com.example.springsupervisorai.service.agent.swarm;

import com.example.springsupervisorai.model.DownstreamCallResult;
import com.example.springsupervisorai.model.HandoffDirective;
import com.example.springsupervisorai.model.HandoffValidationResult;
import com.example.springsupervisorai.model.RoutingPlan;
import com.example.springsupervisorai.model.SupervisorInvocationStatus;
import com.example.springsupervisorai.model.SwarmState;
import com.example.springsupervisorai.service.agent.store.SupervisorSwarmStateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * SwarmState 기반 실행 규칙/이력 갱신 기본 구현.
 * <p>
 * 현재 MVP 규칙:
 * - 최근 실패 에이전트는 짧은 cooldown 동안 라우팅에서 제외
 * - 이벤트 로그는 최근 100개만 유지 (메모리 누수 방지)
 * <p>
 * Circuit Breaker와의 관계:
 * - Circuit Breaker: 연속 실패 시 일정 시간 동안 호출 자체를 차단 (hard block)
 * - Swarm Cooldown: 최근 실패 에이전트를 라우팅 계획에서 우선순위 하락 (soft skip)
 * - 두 메커니즘은 상호 보완적으로 동작:
 *   1. Circuit Breaker가 먼저 동작하여 반복 호출 차단 (invoke 단계)
 *   2. Swarm Cooldown은 라우팅 계획 단계에서 실패 에이전트를 건너뜀 (plan 단계)
 *   3. Circuit Breaker로 차단된 호출은 Swarm에 실패로 기록되어 cooldown 적용
 * <p>
 * 통합 시나리오 예시:
 * - 에이전트 A가 3회 연속 실패 → Circuit Breaker가 30초간 open
 * - open 중에는 호출 자체가 즉시 실패 반환 → Swarm에 실패 기록
 * - 라우팅 계획 시 cooldown 중인 에이전트 A는 건너뛰고 에이전트 B로 우회
 * - 30초 후 Circuit Breaker 자동 복구, cooldown도 120초 후 해제
 */
@Component
public class DefaultSupervisorSwarmCoordinator implements SupervisorSwarmCoordinator {

    private static final Logger logger = LoggerFactory.getLogger(DefaultSupervisorSwarmCoordinator.class);
    private static final String FACT_AGENT_COOLDOWN_UNTIL = "agentCooldownUntilEpochMs";
    private static final String FACT_CIRCUIT_BREAKER_OPEN_UNTIL = "circuitBreakerOpenUntilEpochMs";
    private static final String FACT_HANDOFF_HOP_COUNT = "handoffHopCount";
    private static final String FACT_HANDOFF_PATH = "handoffPath";
    private static final String FACT_HANDOFF_BLOCKED_COUNT = "handoffBlockedCount";
    private static final String FACT_LAST_HANDOFF_AGENT = "lastHandoffAgent";
    private static final String FACT_LAST_HANDOFF_AT = "lastHandoffAt";
    private static final String FACT_HANDOFF_WINDOW_START_EPOCH_MS = "handoffWindowStartEpochMs";
    private static final String FACT_HANDOFF_WINDOW_COUNT = "handoffWindowCount";
    private static final long FAILED_AGENT_COOLDOWN_MS = 120_000L; // Swarm cooldown: 2분
    private static final int MAX_EVENT_LOG_SIZE = 100;
    private static final int MAX_UPSERT_RETRIES = 3;
    private static final long RETRY_BACKOFF_BASE_MS = 10L;

    private final SupervisorSwarmStateStore swarmStateStore;

    public DefaultSupervisorSwarmCoordinator(SupervisorSwarmStateStore swarmStateStore) {
        this.swarmStateStore = swarmStateStore;
    }

    @Override
    public Optional<SwarmState> loadLatestBySession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        return swarmStateStore.loadLatestBySession(sessionId);
    }

    /**
     * {@inheritDoc}
     * <p>
     * 라우팅 필터링 규칙 (우선순위 순):
     * 1. Circuit Breaker open 상태 확인 (circuitBreakerOpenUntilEpochMs)
     * 2. Swarm cooldown 상태 확인 (agentCooldownUntilEpochMs)
     * 3. 둘 중 하나라도 활성화되어 있으면 해당 에이전트를 라우팅에서 제외
     * 4. 모든 에이전트가 차단 중이면 첫 번째 계획을 강제 허용 (무응답 방지)
     */
    @Override
    public List<RoutingPlan> applyRoutingRule(
            String taskId,
            String sessionId,
            List<RoutingPlan> planned,
            Map<String, Object> swarmFacts
    ) {
        if (planned == null || planned.isEmpty()) {
            return List.of();
        }
        Map<String, Long> cooldown = cooldownMap(swarmFacts);
        Map<String, Long> circuitOpen = circuitBreakerMap(swarmFacts);

        if (cooldown.isEmpty() && circuitOpen.isEmpty()) {
            return List.copyOf(planned);
        }

        long now = Instant.now().toEpochMilli();
        List<RoutingPlan> filtered = new ArrayList<>();
        List<String> skippedByCooldown = new ArrayList<>();
        List<String> skippedByCircuit = new ArrayList<>();

        for (RoutingPlan plan : planned) {
            String agentKey = plan.agentKey();

            // Circuit Breaker 확인 (우선순위 높음)
            long circuitOpenUntil = circuitOpen.getOrDefault(agentKey, 0L);
            if (circuitOpenUntil > now) {
                skippedByCircuit.add(agentKey);
                continue;
            }

            // Swarm cooldown 확인
            long cooldownUntil = cooldown.getOrDefault(agentKey, 0L);
            if (cooldownUntil > now) {
                skippedByCooldown.add(agentKey);
                continue;
            }

            filtered.add(plan);
        }

        // 필터링 결과 로깅
        if (!skippedByCircuit.isEmpty() || !skippedByCooldown.isEmpty()) {
            logger.info("Swarm routing filtered sessionId={}, originalPlanCount={}, filteredPlanCount={}, skippedByCircuit={}, skippedByCooldown={}",
                    safe(sessionId), planned.size(), filtered.size(), skippedByCircuit, skippedByCooldown);
            recordNodeEvent(taskId, sessionId, "PLAN", "Swarm routing rule applied", Map.of(
                    "skippedByCircuit", skippedByCircuit.toString(),
                    "skippedByCooldown", skippedByCooldown.toString(),
                    "originalPlanCount", planned.size(),
                    "filteredPlanCount", filtered.size()
            ));
        }

        // 모든 에이전트가 차단 중이면 원본 첫 번째 계획을 강제로 허용
        if (filtered.isEmpty() && !planned.isEmpty()) {
            logger.warn("Swarm routing forced first plan sessionId={}, forcedAgent={}, skippedByCircuit={}, skippedByCooldown={}",
                    safe(sessionId), planned.get(0).agentKey(), skippedByCircuit, skippedByCooldown);
            recordNodeEvent(taskId, sessionId, "PLAN", "All agents blocked, forcing first plan", Map.of(
                    "forcedAgent", planned.get(0).agentKey(),
                    "totalSkippedByCircuit", skippedByCircuit.size(),
                    "totalSkippedByCooldown", skippedByCooldown.size()
            ));
            return List.of(planned.get(0));
        }

        return filtered;
    }

    @Override
    public void recordNodeEvent(
            String taskId,
            String sessionId,
            String nodeType,
            String message,
            Map<String, Object> metadata
    ) {
        upsert(taskId, sessionId, Map.of(
                "lastNode", safe(nodeType),
                "lastNodeMessage", safe(message),
                "lastNodeAt", Instant.now().toString()
        ), "GRAPH_NODE_EVENT", withNodeMetadata(nodeType, message, metadata));
    }

    @Override
    public void recordInvocationBatch(String taskId, String sessionId, List<DownstreamCallResult> results) {
        if (results == null || results.isEmpty()) {
            return;
        }
        upsertWithRetry(taskId, sessionId, base -> {
            Map<String, Long> cooldown = cooldownMap(base.sharedFacts());
            long now = Instant.now().toEpochMilli();
            int failedCount = 0;
            int successCount = 0;
            int handoffRequestedCount = 0;

            for (DownstreamCallResult result : results) {
                String agentKey = safe(result.agentKey());
                if (agentKey.isBlank()) {
                    continue;
                }
                if (isFailure(result)) {
                    failedCount++;
                    cooldown.put(agentKey, now + FAILED_AGENT_COOLDOWN_MS);
                } else {
                    successCount++;
                    cooldown.remove(agentKey);
                }
                if (result.handoffRequested()) {
                    handoffRequestedCount++;
                }
            }

            LinkedHashMap<String, Object> factUpdates = new LinkedHashMap<>();
            factUpdates.put(FACT_AGENT_COOLDOWN_UNTIL, cooldown);
            factUpdates.put("lastInvokeFailedCount", failedCount);
            factUpdates.put("lastInvokeSuccessCount", successCount);
            factUpdates.put("lastInvokeHandoffRequestedCount", handoffRequestedCount);
            factUpdates.put("lastInvokeHandoffAcceptedCount", 0);

            return new SwarmMutation(
                    Map.copyOf(factUpdates),
                    List.of(eventEntry("INVOKE_BATCH_RECORDED", Map.of(
                            "batchSize", results.size(),
                            "failedCount", failedCount,
                            "successCount", successCount,
                            "handoffRequestedCount", handoffRequestedCount,
                            "handoffAcceptedCount", 0
                    )))
            );
        });
    }

    /**
     * handoff 검증 결과를 Swarm facts/eventLog에 반영한다.
     * <p>
     * 부작용:
     * - accepted 건수 기준으로 hop/path/window 사실값을 갱신한다.
     * - rejected 건수 기준으로 blocked 카운트를 증가시킨다.
     * - 검증 결과를 `HANDOFF_REQUESTED/ACCEPTED/REJECTED/HANDOFF_SKIPPED_BY_FLAG` 이벤트로 적재한다.
     * <p>
     * 주의:
     * - 본 메서드는 "정책 검증 결과"를 기준으로만 accepted를 집계한다.
     * - invoke 결과의 단순 `nextAgentKey` 존재 여부로 accepted를 추정하지 않는다.
     *
     * @param taskId supervisor task id
     * @param sessionId 세션 id
     * @param validations handoff 검증 결과 목록
     * @param handoffEnabled 현재 feature flag 상태
     */
    @Override
    public void recordHandoffEvaluations(
            String taskId,
            String sessionId,
            List<HandoffValidationResult> validations,
            boolean handoffEnabled
    ) {
        if (validations == null || validations.isEmpty()) {
            return;
        }
        upsertWithRetry(taskId, sessionId, base -> buildHandoffMutation(base, validations, handoffEnabled));
    }

    private boolean isFailure(DownstreamCallResult result) {
        if (result == null) {
            return true;
        }
        boolean completed = SupervisorInvocationStatus.COMPLETED.value().equalsIgnoreCase(safe(result.status()));
        boolean hasErrorCode = !safe(result.errorCode()).isBlank();
        return !completed || hasErrorCode;
    }

    private Map<String, Object> withNodeMetadata(String nodeType, String message, Map<String, Object> metadata) {
        LinkedHashMap<String, Object> merged = new LinkedHashMap<>();
        merged.put("nodeType", safe(nodeType));
        merged.put("message", safe(message));
        if (metadata != null && !metadata.isEmpty()) {
            metadata.forEach((key, value) -> merged.put(String.valueOf(key), value == null ? "" : value));
        }
        return merged;
    }

    private void upsert(
            String taskId,
            String sessionId,
            Map<String, Object> factUpdates,
            String eventType,
            Map<String, Object> eventMetadata
    ) {
        upsertWithRetry(taskId, sessionId, base -> new SwarmMutation(
                factUpdates == null ? Map.of() : Map.copyOf(factUpdates),
                List.of(eventEntry(eventType, eventMetadata))
        ));
    }

    /**
     * Swarm 상태를 read-merge-write로 저장하되, version 충돌 시 최신 상태로 재계산 후 재시도한다.
     * <p>
     * 재시도 정책:
     * - 최대 {@value #MAX_UPSERT_RETRIES}회
     * - 충돌 시 짧은 선형 backoff 적용
     * - 초과 시 예외를 상위로 전달해 호출자가 실패를 감지할 수 있게 한다.
     */
    private void upsertWithRetry(
            String taskId,
            String sessionId,
            SwarmMutationBuilder mutationBuilder
    ) {
        int attempt = 0;
        while (true) {
            SwarmState base = baseState(taskId, sessionId);
            SwarmMutation mutation = mutationBuilder.build(base);
            try {
                persistMutation(taskId, sessionId, base, mutation);
                return;
            } catch (SwarmStateVersionConflictException conflict) {
                attempt++;
                if (attempt > MAX_UPSERT_RETRIES) {
                    logger.warn(
                            "Swarm upsert conflict exceeded retries taskId={}, sessionId={}, expectedVersion={}, actualVersion={}, retries={}",
                            safe(taskId),
                            safe(sessionId),
                            conflict.getExpectedVersion(),
                            conflict.getActualVersion(),
                            MAX_UPSERT_RETRIES
                    );
                    throw conflict;
                }
                logger.debug(
                        "Swarm upsert conflict retry taskId={}, sessionId={}, attempt={}, expectedVersion={}, actualVersion={}",
                        safe(taskId),
                        safe(sessionId),
                        attempt,
                        conflict.getExpectedVersion(),
                        conflict.getActualVersion()
                );
                backoffBeforeRetry(attempt);
            }
        }
    }

    private void persistMutation(
            String taskId,
            String sessionId,
            SwarmState base,
            SwarmMutation mutation
    ) {
        Map<String, Object> factUpdates = mutation == null ? Map.of() : mutation.factUpdates();
        List<Map<String, Object>> eventEntries = mutation == null ? List.of() : mutation.eventEntries();

        LinkedHashMap<String, Object> mergedFacts = new LinkedHashMap<>(base.sharedFacts());
        if (factUpdates != null && !factUpdates.isEmpty()) {
            factUpdates.forEach((key, value) -> mergedFacts.put(String.valueOf(key), value));
        }

        ArrayList<Map<String, Object>> events = new ArrayList<>(base.eventLog());
        if (eventEntries != null && !eventEntries.isEmpty()) {
            events.addAll(eventEntries);
        }

        // 이벤트 로그 크기 제한: 최근 MAX_EVENT_LOG_SIZE개만 유지
        if (events.size() > MAX_EVENT_LOG_SIZE) {
            events.subList(0, events.size() - MAX_EVENT_LOG_SIZE).clear();
        }

        swarmStateStore.upsert(new SwarmState(
                safe(taskId),
                safe(sessionId),
                base.stateVersion() + 1,
                Instant.now(),
                Map.copyOf(mergedFacts),
                List.copyOf(events)
        ));
    }

    private SwarmMutation buildHandoffMutation(
            SwarmState base,
            List<HandoffValidationResult> validations,
            boolean handoffEnabled
    ) {
        int handoffHopCount = intFact(base.sharedFacts(), FACT_HANDOFF_HOP_COUNT);
        int handoffBlockedCount = intFact(base.sharedFacts(), FACT_HANDOFF_BLOCKED_COUNT);
        int handoffWindowCount = intFact(base.sharedFacts(), FACT_HANDOFF_WINDOW_COUNT);
        long handoffWindowStartEpochMs = longFact(base.sharedFacts(), FACT_HANDOFF_WINDOW_START_EPOCH_MS);
        List<String> handoffPath = listFact(base.sharedFacts(), FACT_HANDOFF_PATH);
        String lastHandoffAgent = safe(base.sharedFacts().get(FACT_LAST_HANDOFF_AGENT) == null ? "" : String.valueOf(base.sharedFacts().get(FACT_LAST_HANDOFF_AGENT)));

        int acceptedCount = 0;
        int requestedCount = 0;
        long now = Instant.now().toEpochMilli();
        List<Map<String, Object>> eventEntries = new ArrayList<>();

        for (HandoffValidationResult validation : validations) {
            if (validation == null || validation.directive() == null) {
                continue;
            }
            HandoffDirective directive = validation.directive();
            String fromAgent = safe(directive.fromAgentKey());
            String toAgent = safe(directive.nextAgentKey());
            String reason = safe(directive.reason());
            requestedCount++;

            eventEntries.add(eventEntry("HANDOFF_REQUESTED", Map.of(
                    "handoffEnabled", handoffEnabled,
                    "fromAgent", fromAgent,
                    "toAgent", toAgent,
                    "reason", reason
            )));

            if (validation.accepted()) {
                acceptedCount++;
                handoffHopCount = Math.max(handoffHopCount, validation.hopCount());
                if (handoffPath.isEmpty() || !safe(handoffPath.get(handoffPath.size() - 1)).equals(fromAgent)) {
                    handoffPath.add(fromAgent);
                }
                handoffPath.add(toAgent);
                lastHandoffAgent = toAgent;
                eventEntries.add(eventEntry("HANDOFF_ACCEPTED", Map.of(
                        "handoffEnabled", handoffEnabled,
                        "fromAgent", fromAgent,
                        "toAgent", toAgent,
                        "reason", reason,
                        "hopCount", validation.hopCount()
                )));
                continue;
            }

            handoffBlockedCount++;
            String reasonCode = safe(validation.reasonCode());
            eventEntries.add(eventEntry(
                    "FLAG_DISABLED".equals(reasonCode) ? "HANDOFF_SKIPPED_BY_FLAG" : "HANDOFF_REJECTED",
                    Map.of(
                            "handoffEnabled", handoffEnabled,
                            "fromAgent", fromAgent,
                            "toAgent", toAgent,
                            "reason", reason,
                            "reasonCode", reasonCode,
                            "hopCount", validation.hopCount()
                    )
            ));
        }

        if (acceptedCount > 0) {
            if (handoffWindowStartEpochMs <= 0 || now - handoffWindowStartEpochMs >= 60_000L) {
                handoffWindowStartEpochMs = now;
                handoffWindowCount = acceptedCount;
            } else {
                handoffWindowCount += acceptedCount;
            }
        } else if (handoffWindowStartEpochMs > 0 && now - handoffWindowStartEpochMs >= 60_000L) {
            handoffWindowStartEpochMs = now;
            handoffWindowCount = 0;
        }

        LinkedHashMap<String, Object> factUpdates = new LinkedHashMap<>();
        factUpdates.put(FACT_HANDOFF_HOP_COUNT, handoffHopCount);
        factUpdates.put(FACT_HANDOFF_BLOCKED_COUNT, handoffBlockedCount);
        factUpdates.put(FACT_HANDOFF_PATH, List.copyOf(handoffPath));
        factUpdates.put(FACT_LAST_HANDOFF_AGENT, lastHandoffAgent);
        factUpdates.put(
                FACT_LAST_HANDOFF_AT,
                acceptedCount > 0
                        ? Instant.now().toString()
                        : safe(base.sharedFacts().get(FACT_LAST_HANDOFF_AT) == null ? "" : String.valueOf(base.sharedFacts().get(FACT_LAST_HANDOFF_AT)))
        );
        factUpdates.put(FACT_HANDOFF_WINDOW_START_EPOCH_MS, handoffWindowStartEpochMs);
        factUpdates.put(FACT_HANDOFF_WINDOW_COUNT, handoffWindowCount);
        factUpdates.put("lastInvokeHandoffRequestedCount", requestedCount);
        factUpdates.put("lastInvokeHandoffAcceptedCount", acceptedCount);

        return new SwarmMutation(Map.copyOf(factUpdates), List.copyOf(eventEntries));
    }

    private void backoffBeforeRetry(int attempt) {
        long backoffMs = Math.min(100L, RETRY_BACKOFF_BASE_MS * Math.max(1, attempt));
        try {
            Thread.sleep(backoffMs);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while retrying swarm state upsert", interrupted);
        }
    }

    @FunctionalInterface
    private interface SwarmMutationBuilder {
        SwarmMutation build(SwarmState base);
    }

    private record SwarmMutation(
            Map<String, Object> factUpdates,
            List<Map<String, Object>> eventEntries
    ) {
    }

    private Map<String, Object> eventEntry(String eventType, Map<String, Object> metadata) {
        LinkedHashMap<String, Object> event = new LinkedHashMap<>();
        event.put("type", safe(eventType));
        event.put("at", Instant.now().toString());
        if (metadata != null && !metadata.isEmpty()) {
            metadata.forEach((key, value) -> event.put(String.valueOf(key), value == null ? "" : value));
        }
        return Map.copyOf(event);
    }

    private SwarmState baseState(String taskId, String sessionId) {
        Optional<SwarmState> currentTask = safe(taskId).isBlank() ? Optional.empty() : swarmStateStore.load(taskId);
        if (currentTask.isPresent()) {
            return currentTask.get();
        }

        Optional<SwarmState> sessionLatest = loadLatestBySession(sessionId);
        if (sessionLatest.isPresent()) {
            SwarmState previous = sessionLatest.get();
            return new SwarmState(
                    safe(taskId),
                    safe(sessionId),
                    previous.stateVersion(),
                    previous.updatedAt(),
                    previous.sharedFacts(),
                    previous.eventLog()
            );
        }

        return new SwarmState(safe(taskId), safe(sessionId), 0L, Instant.now(), Map.of(), List.of());
    }

    /**
     * SwarmState facts에서 에이전트별 cooldown 만료 시각을 추출한다.
     *
     * @param facts swarm shared facts
     * @return agentKey -> cooldown 만료 시각(epochMs) 맵
     */
    @SuppressWarnings("unchecked")
    private Map<String, Long> cooldownMap(Map<String, Object> facts) {
        if (facts == null || facts.isEmpty()) {
            return new LinkedHashMap<>();
        }
        Object raw = facts.get(FACT_AGENT_COOLDOWN_UNTIL);
        if (!(raw instanceof Map<?, ?> source)) {
            return new LinkedHashMap<>();
        }

        LinkedHashMap<String, Long> converted = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key == null || value == null) {
                return;
            }
            try {
                converted.put(String.valueOf(key), Long.parseLong(String.valueOf(value)));
            } catch (NumberFormatException ignored) {
                // malformed data는 무시한다.
            }
        });
        return converted;
    }

    /**
     * SwarmState facts에서 에이전트별 Circuit Breaker open 만료 시각을 추출한다.
     *
     * @param facts swarm shared facts
     * @return agentKey -> circuit open 만료 시각(epochMs) 맵
     */
    @SuppressWarnings("unchecked")
    private Map<String, Long> circuitBreakerMap(Map<String, Object> facts) {
        if (facts == null || facts.isEmpty()) {
            return new LinkedHashMap<>();
        }
        Object raw = facts.get(FACT_CIRCUIT_BREAKER_OPEN_UNTIL);
        if (!(raw instanceof Map<?, ?> source)) {
            return new LinkedHashMap<>();
        }

        LinkedHashMap<String, Long> converted = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key == null || value == null) {
                return;
            }
            try {
                converted.put(String.valueOf(key), Long.parseLong(String.valueOf(value)));
            } catch (NumberFormatException ignored) {
                // malformed data는 무시한다.
            }
        });
        return converted;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private int intFact(Map<String, Object> facts, String key) {
        if (facts == null || facts.isEmpty()) {
            return 0;
        }
        Object raw = facts.get(key);
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
        if (facts == null || facts.isEmpty()) {
            return 0L;
        }
        Object raw = facts.get(key);
        if (raw instanceof Number number) {
            return Math.max(0L, number.longValue());
        }
        try {
            return Math.max(0L, Long.parseLong(String.valueOf(raw)));
        } catch (Exception ignored) {
            return 0L;
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> listFact(Map<String, Object> facts, String key) {
        if (facts == null || facts.isEmpty()) {
            return new ArrayList<>();
        }
        Object raw = facts.get(key);
        if (!(raw instanceof List<?> source) || source.isEmpty()) {
            return new ArrayList<>();
        }
        ArrayList<String> converted = new ArrayList<>();
        for (Object item : source) {
            String value = item == null ? "" : String.valueOf(item);
            if (!value.isBlank()) {
                converted.add(value);
            }
        }
        return converted;
    }
}
