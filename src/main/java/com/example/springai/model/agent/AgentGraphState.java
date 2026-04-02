package com.example.springai.model.agent;

import org.bsc.langgraph4j.state.AgentState;

import java.util.List;
import java.util.Map;

public class AgentGraphState extends AgentState {

    public static final String SESSION_ID = "sessionId";
    public static final String USER_MESSAGE = "userMessage";
    public static final String MODEL = "model";
    public static final String HISTORY = "history";
    public static final String CHECKPOINT_ID = "checkpointId";
    public static final String CURRENT_NODE = "currentNode";
    public static final String PLANS = "plans";

    public static final String PLAN_CAPABILITY = "planCapability";
    public static final String PLAN_SERVER = "planServer";
    public static final String PLAN_TOOL = "planTool";
    public static final String PLAN_REASON = "planReason";
    public static final String PLAN_REQUIRED = "planRequired";

    public static final String EXEC_SERVER = "execServer";
    public static final String EXEC_TOOL = "execTool";
    public static final String EXEC_PAYLOAD = "execPayload";
    public static final String EXEC_ARGS = "execArgs";
    public static final String EXEC_TRACE = "execTrace";
    public static final String EXEC_SUCCESS = "execSuccess";
    public static final String EXEC_EXECUTED = "execExecuted";

    public AgentGraphState(Map<String, Object> initData) {
        super(initData);
    }

    public String sessionId() {
        return value(SESSION_ID).map(String.class::cast).orElse("");
    }

    public String userMessage() {
        return value(USER_MESSAGE).map(String.class::cast).orElse("");
    }

    public String model() {
        return value(MODEL).map(String.class::cast).orElse("openai");
    }

    @SuppressWarnings("unchecked")
    public List<String> history() {
        Object raw = value(HISTORY).orElse(List.of());
        if (raw instanceof List<?> list) {
            return (List<String>) list;
        }
        return List.of();
    }

    public String checkpointId() {
        return value(CHECKPOINT_ID).map(String.class::cast).orElse("");
    }

    public PlanningContext toPlanningContext() {
        PlanningContext context = new PlanningContext(sessionId(), userMessage(), model());
        context.replaceHistory(history());
        context.setCheckpointId(checkpointId());
        context.setCurrentNode(value(CURRENT_NODE).map(String.class::cast).orElse("COMPOSING"));

        ToolPlan plan = new ToolPlan(
                value(PLAN_CAPABILITY).map(String.class::cast).orElse("none"),
                value(PLAN_SERVER).map(String.class::cast).orElse(""),
                value(PLAN_TOOL).map(String.class::cast).orElse(""),
                value(PLAN_REASON).map(String.class::cast).orElse(""),
                Map.of(),
                value(PLAN_REQUIRED).map(Boolean.class::cast).orElse(false)
        );
        context.setPlans(parsePlans().isEmpty() ? List.of(plan) : parsePlans());

        ToolExecutionResult executionResult = new ToolExecutionResult(
                value(EXEC_SERVER).map(String.class::cast).orElse(""),
                value(EXEC_TOOL).map(String.class::cast).orElse(""),
                value(EXEC_PAYLOAD).map(String.class::cast).orElse(""),
                readMapFromState(EXEC_ARGS),
                value(EXEC_SUCCESS).map(Boolean.class::cast).orElse(true),
                value(EXEC_EXECUTED).map(Boolean.class::cast).orElse(false)
        );
        context.setExecutionResult(executionResult);
        for (String trace : readStringListFromState(EXEC_TRACE)) {
            context.addToolTrace(trace);
        }
        return context;
    }

    @SuppressWarnings("unchecked")
    private List<ToolPlan> parsePlans() {
        Object raw = value(PLANS).orElse(List.of());
        if (!(raw instanceof List<?> planList)) {
            return List.of();
        }

        List<ToolPlan> result = new java.util.ArrayList<>();
        for (Object item : planList) {
            if (item instanceof Map<?, ?> map) {
                result.add(new ToolPlan(
                        readString(map, "capability", "none"),
                        readString(map, "serverName", ""),
                        readString(map, "toolName", ""),
                        readString(map, "reason", ""),
                        readMap(map, "arguments"),
                        Boolean.parseBoolean(readString(map, "toolRequired", "false"))
                ));
            }
        }
        return result;
    }

    private String readString(Map<?, ?> map, String key, String defaultValue) {
        Object value = map.get(key);
        return value == null ? defaultValue : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        Map<String, Object> converted = new java.util.HashMap<>();
        raw.forEach((k, v) -> converted.put(String.valueOf(k), v));
        return converted;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMapFromState(String key) {
        Object value = value(key).orElse(Map.of());
        if (!(value instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        Map<String, Object> converted = new java.util.HashMap<>();
        raw.forEach((k, v) -> converted.put(String.valueOf(k), v));
        return converted;
    }

    @SuppressWarnings("unchecked")
    private List<String> readStringListFromState(String key) {
        Object raw = value(key).orElse(List.of());
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<String> result = new java.util.ArrayList<>();
        for (Object item : list) {
            if (item != null) {
                result.add(String.valueOf(item));
            }
        }
        return result;
    }
}
