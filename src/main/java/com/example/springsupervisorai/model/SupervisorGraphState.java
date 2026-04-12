package com.example.springsupervisorai.model;

import org.bsc.langgraph4j.state.AgentState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class SupervisorGraphState extends AgentState {

    public static final String SESSION_ID = "sessionId";
    public static final String USER_MESSAGE = "userMessage";
    public static final String MODEL = "model";
    public static final String HISTORY = "history";
    public static final String CHECKPOINT_ID = "checkpointId";
    public static final String CURRENT_NODE = "currentNode";
    public static final String ROUTING_PLANS = "routingPlans";
    public static final String ROUTING_INDEX = "routingIndex";
    public static final String CURRENT_PLAN = "currentPlan";
    public static final String DOWNSTREAM_RESULTS = "downstreamResults";

    public SupervisorGraphState(Map<String, Object> initData) {
        super(initData);
    }

    public SupervisorPlanningContext toPlanningContext() {
        SupervisorPlanningContext context = new SupervisorPlanningContext(
                value(SESSION_ID).map(String.class::cast).orElse(""),
                value(USER_MESSAGE).map(String.class::cast).orElse(""),
                value(MODEL).map(String.class::cast).orElse("openai")
        );
        context.replaceHistory(readStringList(HISTORY));
        context.setCheckpointId(value(CHECKPOINT_ID).map(String.class::cast).orElse(""));
        context.setCurrentNode(value(CURRENT_NODE).map(String.class::cast).orElse(SupervisorRuntimeState.REQUEST_VALIDATED.value()));
        context.setRoutingPlans(readPlans());
        context.setResults(readResults());
        context.setRoutingIndex(value(ROUTING_INDEX).map(Integer.class::cast).orElse(0));
        return context;
    }

    @SuppressWarnings("unchecked")
    private List<String> readStringList(String key) {
        Object raw = value(key).orElse(List.of());
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

    @SuppressWarnings("unchecked")
    private List<RoutingPlan> readPlans() {
        Object raw = value(ROUTING_PLANS).orElse(List.of());
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
                    readMap(map, "arguments")
            ));
        }
        return plans;
    }

    @SuppressWarnings("unchecked")
    private List<DownstreamCallResult> readResults() {
        Object raw = value(DOWNSTREAM_RESULTS).orElse(List.of());
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<DownstreamCallResult> results = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            results.add(new DownstreamCallResult(
                    readString(map, "agentKey"),
                    readString(map, "taskId"),
                    readString(map, "status"),
                    readString(map, "payload"),
                    readString(map, "errorCode"),
                    readString(map, "errorMessage")
            ));
        }
        return results;
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof Map<?, ?> source)) {
            return Map.of();
        }
        java.util.LinkedHashMap<String, Object> converted = new java.util.LinkedHashMap<>();
        source.forEach((k, v) -> converted.put(String.valueOf(k), v));
        return converted;
    }
}
