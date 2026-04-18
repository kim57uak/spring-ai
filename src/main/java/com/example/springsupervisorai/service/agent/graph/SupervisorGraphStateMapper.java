package com.example.springsupervisorai.service.agent.graph;

import com.example.springsupervisorai.model.DownstreamCallResult;
import com.example.springsupervisorai.model.HandoffDirective;
import com.example.springsupervisorai.model.HandoffValidationResult;
import com.example.springsupervisorai.model.RoutingPlan;
import com.example.springsupervisorai.model.SupervisorGraphSnapshot;
import com.example.springsupervisorai.model.SupervisorGraphState;
import com.example.springsupervisorai.model.SupervisorPlanningContext;
import com.example.springsupervisorai.model.SupervisorRuntimeState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Supervisor graph 상태와 도메인 타입 간 변환을 담당한다.
 */
public final class SupervisorGraphStateMapper {

    public static final SupervisorGraphStateMapper INSTANCE = new SupervisorGraphStateMapper();

    private SupervisorGraphStateMapper() {
    }

    public SupervisorGraphSnapshot snapshot(SupervisorGraphState state) {
        return new SupervisorGraphSnapshot(
                state.value(SupervisorGraphState.TASK_ID).map(String.class::cast).orElse(""),
                state.value(SupervisorGraphState.SESSION_ID).map(String.class::cast).orElse(""),
                state.value(SupervisorGraphState.USER_MESSAGE).map(String.class::cast).orElse(""),
                state.value(SupervisorGraphState.MODEL).map(String.class::cast).orElse("openai"),
                readStringList(state.value(SupervisorGraphState.HISTORY).orElse(List.of())),
                state.value(SupervisorGraphState.CHECKPOINT_ID).map(String.class::cast).orElse(""),
                state.value(SupervisorGraphState.CURRENT_NODE).map(String.class::cast).orElse(SupervisorRuntimeState.REQUEST_VALIDATED.value()),
                readPlans(state.value(SupervisorGraphState.ROUTING_PLANS).orElse(List.of())),
                state.value(SupervisorGraphState.ROUTING_INDEX).map(Integer.class::cast).orElse(0),
                readResults(state.value(SupervisorGraphState.DOWNSTREAM_RESULTS).orElse(List.of())),
                readResults(state.value(SupervisorGraphState.LAST_INVOKE_BATCH_RESULTS).orElse(List.of())),
                readHandoffValidations(state.value(SupervisorGraphState.HANDOFF_VALIDATIONS).orElse(List.of())),
                toBoolean(state.value(SupervisorGraphState.HANDOFF_ENABLED).orElse(false)),
                readFlatMap(state.value(SupervisorGraphState.SWARM_SHARED_FACTS).orElse(Map.of())),
                readLong(state.value(SupervisorGraphState.SWARM_STATE_VERSION).orElse(0L))
        );
    }

    public SupervisorPlanningContext toPlanningContext(SupervisorGraphState state) {
        return toPlanningContext(snapshot(state));
    }

    public SupervisorPlanningContext toPlanningContext(SupervisorGraphSnapshot snapshot) {
        SupervisorPlanningContext context = new SupervisorPlanningContext(
                snapshot.taskId(),
                snapshot.sessionId(),
                snapshot.userMessage(),
                snapshot.model()
        );
        context.replaceHistory(snapshot.history());
        context.setCheckpointId(snapshot.checkpointId());
        context.setCurrentNode(snapshot.currentNode());
        context.setRoutingPlans(snapshot.routingPlans());
        context.setResults(snapshot.results());
        context.setRoutingIndex(snapshot.routingIndex());
        context.setSwarmSharedFacts(snapshot.swarmSharedFacts());
        context.setSwarmStateVersion(snapshot.swarmStateVersion());
        return context;
    }

    public List<Map<String, Object>> toPlanList(List<RoutingPlan> plans) {
        return plans.stream().map(this::toPlanMap).toList();
    }

    /**
     * typed snapshot을 graph state map으로 직렬화한다.
     *
     * @param snapshot typed graph snapshot
     * @return graph invoke/checkpoint에 사용할 map 표현
     */
    public Map<String, Object> toStateMap(SupervisorGraphSnapshot snapshot) {
        LinkedHashMap<String, Object> mapped = new LinkedHashMap<>();
        mapped.put(SupervisorGraphState.TASK_ID, snapshot.taskId());
        mapped.put(SupervisorGraphState.SESSION_ID, snapshot.sessionId());
        mapped.put(SupervisorGraphState.USER_MESSAGE, snapshot.userMessage());
        mapped.put(SupervisorGraphState.MODEL, snapshot.model());
        mapped.put(SupervisorGraphState.HISTORY, snapshot.history());
        mapped.put(SupervisorGraphState.CHECKPOINT_ID, snapshot.checkpointId());
        mapped.put(SupervisorGraphState.CURRENT_NODE, snapshot.currentNode());
        mapped.put(SupervisorGraphState.ROUTING_PLANS, toPlanList(snapshot.routingPlans()));
        mapped.put(SupervisorGraphState.ROUTING_INDEX, snapshot.routingIndex());
        mapped.put(SupervisorGraphState.DOWNSTREAM_RESULTS, toResultList(snapshot.results()));
        mapped.put(SupervisorGraphState.LAST_INVOKE_BATCH_RESULTS, toResultList(snapshot.lastInvokeBatchResults()));
        mapped.put(SupervisorGraphState.HANDOFF_VALIDATIONS, toHandoffValidationList(snapshot.handoffValidations()));
        mapped.put(SupervisorGraphState.HANDOFF_ENABLED, snapshot.handoffEnabled());
        mapped.put(SupervisorGraphState.SWARM_SHARED_FACTS, snapshot.swarmSharedFacts());
        mapped.put(SupervisorGraphState.SWARM_STATE_VERSION, snapshot.swarmStateVersion());
        return Map.copyOf(mapped);
    }

    public Map<String, Object> toPlanMap(RoutingPlan plan) {
        return Map.of(
                "agentKey", safe(plan.agentKey()),
                "method", safe(plan.method()),
                "reason", safe(plan.reason()),
                "priority", plan.priority(),
                "arguments", plan.arguments() == null ? Map.of() : plan.arguments(),
                "sourceType", safe(plan.sourceType()),
                "handoffDepth", plan.handoffDepth(),
                "parentAgentKey", safe(plan.parentAgentKey())
        );
    }

    public List<Map<String, Object>> toResultList(List<DownstreamCallResult> results) {
        return results.stream()
                .map(result -> {
                    Map<String, Object> mapped = new LinkedHashMap<>();
                    mapped.put("agentKey", safe(result.agentKey()));
                    mapped.put("taskId", safe(result.taskId()));
                    mapped.put("status", safe(result.status()));
                    mapped.put("payload", safe(result.payload()));
                    mapped.put("errorCode", safe(result.errorCode()));
                    mapped.put("errorMessage", safe(result.errorMessage()));
                    mapped.put("handoffRequested", result.handoffRequested());
                    mapped.put("nextAgentKey", safe(result.nextAgentKey()));
                    mapped.put("handoffMethod", safe(result.handoffMethod()));
                    mapped.put("handoffReason", safe(result.handoffReason()));
                    mapped.put("handoffArguments", result.handoffArguments() == null ? Map.of() : result.handoffArguments());
                    return mapped;
                })
                .toList();
    }

    public List<Map<String, Object>> toHandoffValidationList(List<HandoffValidationResult> validations) {
        if (validations == null || validations.isEmpty()) {
            return List.of();
        }
        return validations.stream().map(validation -> {
            Map<String, Object> mapped = new LinkedHashMap<>();
            mapped.put("accepted", validation.accepted());
            mapped.put("reasonCode", safe(validation.reasonCode()));
            mapped.put("hopCount", validation.hopCount());

            HandoffDirective directive = validation.directive();
            if (directive != null) {
                mapped.put("fromAgentKey", safe(directive.fromAgentKey()));
                mapped.put("nextAgentKey", safe(directive.nextAgentKey()));
                mapped.put("method", safe(directive.method()));
                mapped.put("reason", safe(directive.reason()));
                mapped.put("arguments", directive.arguments() == null ? Map.of() : directive.arguments());
            } else {
                mapped.put("fromAgentKey", "");
                mapped.put("nextAgentKey", "");
                mapped.put("method", "");
                mapped.put("reason", "");
                mapped.put("arguments", Map.of());
            }

            RoutingPlan plan = validation.plan();
            mapped.put("plan", plan == null ? Map.of() : toPlanMap(plan));
            return mapped;
        }).toList();
    }

    public List<DownstreamCallResult> readResults(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        ArrayList<DownstreamCallResult> converted = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            converted.add(new DownstreamCallResult(
                    readString(map, "agentKey"),
                    readString(map, "taskId"),
                    readString(map, "status"),
                    readString(map, "payload"),
                    readString(map, "errorCode"),
                    readString(map, "errorMessage"),
                    readBoolean(map, "handoffRequested"),
                    readString(map, "nextAgentKey"),
                    readString(map, "handoffMethod"),
                    readString(map, "handoffReason"),
                    readMap(map, "handoffArguments")
            ));
        }
        return converted;
    }

    public List<HandoffValidationResult> readHandoffValidations(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        ArrayList<HandoffValidationResult> converted = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            HandoffDirective directive = new HandoffDirective(
                    readString(map, "fromAgentKey"),
                    readString(map, "nextAgentKey"),
                    readString(map, "method"),
                    readString(map, "reason"),
                    readMap(map, "arguments")
            );
            Map<String, Object> planMap = readMap(map, "plan");
            RoutingPlan plan = planMap.isEmpty() ? null : new RoutingPlan(
                    readString(planMap, "agentKey"),
                    readString(planMap, "method"),
                    readString(planMap, "reason"),
                    readInt(planMap, "priority"),
                    readMap(planMap, "arguments"),
                    readString(planMap, "sourceType"),
                    readInt(planMap, "handoffDepth"),
                    readString(planMap, "parentAgentKey")
            );
            converted.add(new HandoffValidationResult(
                    readBoolean(map, "accepted"),
                    readString(map, "reasonCode"),
                    directive,
                    plan,
                    readInt(map, "hopCount")
            ));
        }
        return converted;
    }

    private List<RoutingPlan> readPlans(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<RoutingPlan> plans = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            plans.add(new RoutingPlan(
                    readString(map, "agentKey"),
                    readString(map, "method"),
                    readString(map, "reason"),
                    readInt(map, "priority"),
                    readMap(map, "arguments"),
                    readString(map, "sourceType"),
                    readInt(map, "handoffDepth"),
                    readString(map, "parentAgentKey")
            ));
        }
        return plans;
    }

    private List<String> readStringList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (item != null) {
                result.add(String.valueOf(item));
            }
        }
        return result;
    }

    private Map<String, Object> readFlatMap(Object raw) {
        if (!(raw instanceof Map<?, ?> source)) {
            return Map.of();
        }
        LinkedHashMap<String, Object> converted = new LinkedHashMap<>();
        source.forEach((k, v) -> converted.put(String.valueOf(k), v));
        return converted;
    }

    private Map<String, Object> readMap(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof Map<?, ?> source)) {
            return Map.of();
        }
        LinkedHashMap<String, Object> converted = new LinkedHashMap<>();
        source.forEach((k, v) -> converted.put(String.valueOf(k), v));
        return converted;
    }

    private boolean readBoolean(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        return "true".equalsIgnoreCase(String.valueOf(value));
    }

    private boolean toBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return "true".equalsIgnoreCase(String.valueOf(value));
    }

    private String readString(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private int readInt(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private long readLong(Object raw) {
        if (raw instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(raw));
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
