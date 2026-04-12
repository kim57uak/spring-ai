package com.example.springsupervisorai.service.agent.swarm;

import com.example.springsupervisorai.model.DownstreamCallResult;
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
    private static final long FAILED_AGENT_COOLDOWN_MS = 120_000L; // Swarm cooldown: 2분
    private static final int MAX_EVENT_LOG_SIZE = 100;

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
        SwarmState base = baseState(taskId, sessionId);
        Map<String, Long> cooldown = cooldownMap(base.sharedFacts());
        long now = Instant.now().toEpochMilli();
        int failedCount = 0;
        int successCount = 0;

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
        }

        upsert(taskId, sessionId, Map.of(
                FACT_AGENT_COOLDOWN_UNTIL, cooldown,
                "lastInvokeFailedCount", failedCount,
                "lastInvokeSuccessCount", successCount
        ), "INVOKE_BATCH_RECORDED", Map.of(
                "batchSize", results.size(),
                "failedCount", failedCount,
                "successCount", successCount
        ));
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
        SwarmState base = baseState(taskId, sessionId);
        LinkedHashMap<String, Object> mergedFacts = new LinkedHashMap<>(base.sharedFacts());
        if (factUpdates != null && !factUpdates.isEmpty()) {
            factUpdates.forEach((key, value) -> mergedFacts.put(String.valueOf(key), value));
        }

        ArrayList<Map<String, Object>> events = new ArrayList<>(base.eventLog());
        LinkedHashMap<String, Object> event = new LinkedHashMap<>();
        event.put("type", safe(eventType));
        event.put("at", Instant.now().toString());
        if (eventMetadata != null && !eventMetadata.isEmpty()) {
            eventMetadata.forEach((key, value) -> event.put(String.valueOf(key), value == null ? "" : value));
        }
        events.add(event);

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
}
